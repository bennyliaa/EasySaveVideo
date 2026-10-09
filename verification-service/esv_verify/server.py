"""Minimal HTTP/1.1 endpoint with strict finite framing and no request logging."""
import select
import socket
import socketserver
import threading
import time
from .protocol import MAX_REQUEST, Request, InvalidRequest, encode_result
from .service import VerificationService

MAX_HEADERS = 16384
READ_SECONDS = 1.0


class FramingError(Exception):
    def __init__(self, status=400):
        self.status = status


def receive_request(connection):
    deadline = time.monotonic() + READ_SECONDS
    data = bytearray()
    while b'\r\n\r\n' not in data:
        connection.settimeout(max(.001, deadline - time.monotonic()))
        chunk = connection.recv(min(4096, MAX_HEADERS + 1 - len(data)))
        if not chunk or time.monotonic() > deadline:
            raise FramingError()
        data.extend(chunk)
        if len(data) > MAX_HEADERS:
            raise FramingError(431)
    head, body = bytes(data).split(b'\r\n\r\n', 1)
    try:
        lines = head.decode('ascii').split('\r\n')
    except UnicodeError as error:
        raise FramingError() from error
    if lines[0] != 'POST /v1/verify HTTP/1.1':
        raise FramingError(404)
    headers = {}
    for line in lines[1:]:
        key, separator, value = line.partition(':')
        if (not separator or not key or any(not (c.isalnum() or c == '-') for c in key)
                or key.lower() in headers or any(ord(c) < 32 or ord(c) == 127 for c in value)):
            raise FramingError()
        headers[key.lower()] = value.strip()
    if (headers.get('content-type') != 'application/json' or not headers.get('host')
            or set(headers).intersection({'transfer-encoding', 'content-encoding', 'cookie', 'authorization', 'proxy-authorization', 'expect'})
            or headers.get('accept-encoding', 'identity') != 'identity'):
        raise FramingError()
    length = headers.get('content-length', '')
    if not length.isascii() or not length.isdecimal() or len(length) > 5:
        raise FramingError()
    length = int(length)
    if not 1 <= length <= MAX_REQUEST:
        raise FramingError(413)
    if len(body) > length:
        raise FramingError()
    while len(body) < length:
        connection.settimeout(max(.001, deadline - time.monotonic()))
        chunk = connection.recv(length - len(body))
        if not chunk or time.monotonic() > deadline:
            raise FramingError()
        body += chunk
    return Request.parse(body)


def send_response(connection, status, body=b''):
    reason = {200: 'OK', 400: 'Bad Request', 404: 'Not Found', 413: 'Content Too Large',
              431: 'Request Header Fields Too Large', 503: 'Service Unavailable'}.get(status, 'Error')
    header = (f'HTTP/1.1 {status} {reason}\r\nContent-Type: application/json\r\n'
              f'Content-Length: {len(body)}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n').encode()
    connection.settimeout(.2)
    connection.sendall(header + body)


class Handler(socketserver.BaseRequestHandler):
    def handle(self):
        accepted_at = time.monotonic()
        try:
            request = receive_request(self.request)
            self.request.setblocking(False)

            def cancelled():
                ready, _, _ = select.select([self.request], [], [], 0)
                if not ready:
                    return False
                try:
                    # EOF, pipelining, and unsolicited input all invalidate this
                    # one-shot connection. No additional request is interpreted.
                    self.request.recv(1, socket.MSG_PEEK)
                    return True
                except BlockingIOError:
                    return False
                except OSError:
                    return True

            result = self.server.service.verify(request, cancelled, accepted_at)
            if not cancelled():
                send_response(self.request, 200, encode_result(request, result))
        except FramingError as error:
            send_response(self.request, error.status)
        except InvalidRequest:
            send_response(self.request, 400)
        except (OSError, TimeoutError):
            pass


class Server(socketserver.ThreadingMixIn, socketserver.TCPServer):
    allow_reuse_address = False
    daemon_threads = False
    block_on_close = True
    request_queue_size = 4

    def __init__(self, address=('127.0.0.1', 0), service=None):
        if address[0] != '127.0.0.1':
            raise ValueError('LOOPBACK_ONLY')
        self.service = service if service is not None else VerificationService()
        self._connections = threading.BoundedSemaphore(4)
        super().__init__(address, Handler)

    def process_request(self, request, client_address):
        if not self._connections.acquire(blocking=False):
            try:
                send_response(request, 503)
            except OSError:
                pass
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except BaseException:
            self._connections.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self._connections.release()

    def handle_error(self, request, client_address):
        pass

    def server_close(self):
        self.service.close()
        super().server_close()
