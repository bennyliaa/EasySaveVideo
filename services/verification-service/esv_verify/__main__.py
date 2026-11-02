"""Development-only loopback shell; no environment/CLI fixture admission knobs."""
import argparse
from .server import Server


def main():
    parser = argparse.ArgumentParser(description='Optional verifier (all target sources unsupported by default).')
    parser.add_argument('--port', type=int, default=8765)
    args = parser.parse_args()
    if not 1024 <= args.port <= 65535:
        parser.error('port must be 1024..65535')
    with Server(('127.0.0.1', args.port)) as server:
        try:
            server.serve_forever(poll_interval=.1)
        except KeyboardInterrupt:
            pass


if __name__ == '__main__':
    main()
