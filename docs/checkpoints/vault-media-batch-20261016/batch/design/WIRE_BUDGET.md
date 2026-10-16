# Bounded v2 wire sizing proposal

This is a proposed canonical layout to freeze before implementation, not executed Kotlin codec or accepted implementation. It supplies the capacity basis for the temporary eight-item limit. Keep this layout or produce an equally strict reviewed bound before coding. All lengths below are bytes, include four-byte length prefixes, and use existing canonical primitive encodings. Optional values use one boolean byte. Current operation kind is COPY_OUT; its ordinary source and ciphertext-evidence fields must be absent.

Constants: eight rows maximum; batch-generated IDs/operation IDs/grant IDs/nonces exactly 32 lowercase ASCII hex bytes; existing object IDs at most 64 ASCII bytes; URI strings at most 1,024 UTF-8 bytes; SHA-256 text exactly 64 ASCII bytes; no display names, free text, arbitrary reason strings, extra URIs or unbounded metadata in rows.

Proposed per-row worst shape:
- Object ID: 4 + 64 = 68
- Generation: 8
- Fixed planned operation ID: 4 + 32 = 36
- Row state and bounded failure reason: 4 + 4 = 8
- Optional activated sequence: 1 + 8 = 9
- Optional successful plaintext evidence: 1 + 8 + 4 + 64 = 77
- Optional exact result target: 1 + [three 36-byte IDs + URI-present byte + 1,028-byte URI + 4 flag bytes] = 1,142
- Total: 1,348 per row; eight rows = 10,784.

Batch wrapper worst shape:
- Batch ID 36; selected catalog revision 8; fixed profile version 4; count 4; active index 4; run count 4; pause/stop state 4; batch status 4; canonical immutable-plan digest blob 36.
- Optional tree: 1 + [three 36-byte identities + 1,028-byte URI + 4 flag bytes] = 1,141.
- Total wrapper excluding rows: 1,245; wrapper plus eight rows: 12,029.

Existing scalar COPY_OUT payload upper bound with batch-size identities/URIs:
- Magic + version = 8.
- Binding: operation kind 4 + operation ID 36 + sequence 8 + expected catalog revision 8 + source-absent 1 + object-present 1 + object ID 68 + generation 8 = 134.
- Phase 4 + cancel 1 + target-present 1 + target 1,141 + plaintext-present 1 + plaintext evidence 76 + ciphertext-absent 1 = 1,225.
- Add batch-present 1 and batch wrapper 12,029.
- Total proposed payload upper bound: 13,397 bytes, below 14,336 by 939 bytes. This deliberately counts a terminal active target in BOTH scalar and result row.

The v2 encrypted binding adds batch ID, immutable-plan digest, and active index to the existing binding, staying well below the existing 1,024-byte binding cap. Two fixed root-AEAD encryptions, envelope framing, and encrypted binding remain below the existing 16,384-byte envelope cap; implementation must verify actual Tink output size with the existing root-AEAD fixture, not replace this check with arithmetic assumptions.

Required before source integration:
1. Freeze the exact field order/version and make the Kotlin encoder reproduce the bound or a smaller proven value.
2. Test actual canonical worst-shape encode and envelope size; fail on even one extra byte at the configured caps.
3. Decode with count/size validation before allocations. Derive/validate plan digest from canonical immutable data, never accept caller-declared digest as authority.
4. Preflight worst future shape before picker/create. Any extra field or increased bound must redo these tests; never silently increase snapshot or crypto envelope limits.

These limits do not cap the user's total Vault collection or total exports. Larger selections can be divided into newly confirmed batches after earlier results are retained safely.
