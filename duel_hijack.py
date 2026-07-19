# SF3 Duel Hijack — TryHackMe challenge script
# Brawler auto-win loop with automatic re-login when session drops.
# Borrows login logic from sf3_automaticbattlewin.py.

import socket, struct, zlib, hashlib, time, re, sys, os

# ── Configuration ─────────────────────────────────────────────────────────────

HOSTS = [
    "ec2-13-126-233-176.ap-south-1.compute.amazonaws.com",
    "ec2-52-66-28-201.ap-south-1.compute.amazonaws.com",
]
PORT = 443

# ── Account identity ──────────────────────────────────────────────────────────
# Change GUID to use a different game account.
# Use --new-guid flag at runtime to generate a fresh account automatically.
GUID         = "289a6ff9-15d5-48aa-95f2-dfcfebedfab8"
DEVICE_MODEL = "samsung SM-X115"
ANDROID_ID   = "719e3418841cabcd"
GAME_VERSION = "1.45.0.3.16663-prod"

TARGET_WINS       = 50     # stop after this many wins
RETRY_DELAY       = 5.0    # seconds between reconnect attempts
WIN_DELAY         = 1.0    # seconds between wins (avoid rate limit)
STUCK_CLEAR_DELAY = 30.0   # seconds to wait if stuck brawler can't be cleared

# File where we persist the last server_fight_inner so reconnects can clear stuck brawlers
FIGHT_INNER_CACHE = ".last_brawler_inner.bin"

# Fixed handshake — never changes between sessions
HANDSHAKE_TX = bytes.fromhex(
    "011b0801120948414e445348414b451a0c0a0a5346412d4e4542552d31"
)

# Captured login inner — password bytes replaced at runtime
ORIGINAL_INNER = bytes.fromhex(
    "0806"
    "12620801125e"
    "7b226c6f67696e223a2232383961366666392d313564352d343861612d393566322d"
    "646663666562656466616238222c2270617373776f7264223a22"
    "6536633666656561653932363663306335343439353632396663636236623433"
    "22"
    "7d"
    "1a7b" "080d" "1277"
    "7b2274223a22342f3041646b564c50776557587974552d6f2d6b517a386b5a31"
    "6871615050657859754b5258707149533979454a6e7651775058443245475a64"
    "71434d5357755f353437714f555751222c226964223a22673136363133383934"
    "363134373335323534393337222c2265223a747275657d"
    "1a14" "0806" "1210" "3731396533343138383431636162636422"
    "dc01"
    "7b22706c6174666f726d223a22416e64726f6964222c2276223a223139323032"
    "222c226170705f6964223a22636f6d2e6e656b6b692e736861646f7766696768"
    "7433222c2266223a224630303046414236353444434336463830413541413136"
    "41373334434345323444423244323338" "43222c22626e756d626572223a223139"
    "323032222c22626e616d65223a22556e697479436c69656e745f536861646f77"
    "4669676874335f556e697479436c69656e74536861646f7746696768743352656c"
    "656173655f436f6e66696775726174696f6e416e64726f6964227d"
)
ORIGINAL_PASSWORD = b"e6c6feeae9266c0c54495629fccb6b43"
ORIGINAL_GUID     = b"289a6ff9-15d5-48aa-95f2-dfcfebedfab8"  # matches ORIGINAL_INNER bytes

# ── Fixed brawler_finish fields (identical across all captured sessions) ────────
# f[1] is dynamic: the server's brawler_start inner echoed back verbatim.
# f[2]–f[7] are constant — verified across 6 consecutive captures.
_BRAWLER_ROUNDS = bytes.fromhex(
    "08031001"   # round 3, result 1
    "08041002"   # round 4, result 2
    "08051003"   # round 5, result 3
    "08061002"   # round 6, result 2
    "0807"       # round 7
)
_BRAWLER_ITEMS = bytes.fromhex(
    "0a0508d10c10010a0508d20c10010a0508d93410020a0508dc341002"
)
_BRAWLER_STATS = bytes.fromhex(
    "0802101f1a020101220209052a02010132080000803f0000803f"
    "3a08000000000000000042086666e63e6666e63e4a02030352020000"
)

# ── Protobuf helpers ───────────────────────────────────────────────────────────

def enc_varint(v: int) -> bytes:
    out = b""
    while True:
        bits = v & 0x7F; v >>= 7
        out += bytes([bits | (0x80 if v else 0)])
        if not v: break
    return out

def enc_str(fn: int, s: str) -> bytes:
    e = s.encode()
    return enc_varint((fn << 3) | 2) + enc_varint(len(e)) + e

def enc_bytes(fn: int, raw: bytes) -> bytes:
    return enc_varint((fn << 3) | 2) + enc_varint(len(raw)) + raw

def enc_varint_field(fn: int, v: int) -> bytes:
    return enc_varint((fn << 3) | 0) + enc_varint(v)

def read_varint(data: bytes, pos: int):
    val, shift = 0, 0
    while pos < len(data):
        b = data[pos]; pos += 1
        val |= (b & 0x7F) << shift; shift += 7
        if not (b & 0x80): break
    return val, pos

def decode_proto(data: bytes):
    fields, pos = [], 0
    while pos < len(data):
        try:
            tag, pos = read_varint(data, pos); fn = tag >> 3; wire = tag & 7
            if fn == 0: break
            if wire == 0:
                val, pos = read_varint(data, pos); fields.append((fn, 0, val))
            elif wire == 2:
                length, pos = read_varint(data, pos)
                if pos + length > len(data): break
                fields.append((fn, 2, data[pos:pos+length])); pos += length
            else: break
        except Exception: break
    return fields

def try_utf8(raw: bytes):
    try:
        s = raw.decode("utf-8")
        if all(32 <= ord(c) < 127 or c in "\t\n\r" for c in s): return s
    except Exception: pass
    return None

# ── Packet framing ─────────────────────────────────────────────────────────────

def small_pkt(payload: bytes) -> bytes:
    assert len(payload) <= 255
    return bytes([0x01, len(payload)]) + payload

def large_pkt(payload: bytes) -> bytes:
    compressed = zlib.compress(payload, 6)[2:-4]
    return bytes([0x02]) + struct.pack("<I", len(compressed)) + compressed

def build_outer(counter: int, cmd: str, inner: bytes = b"") -> bytes:
    p  = enc_varint_field(1, counter)
    p += enc_str(2, cmd)
    if inner: p += enc_bytes(3, inner)
    return p

def recv_exact(sock, n: int):
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk: return None
        buf += chunk
    return buf

def recv_packet(sock):
    hdr = recv_exact(sock, 1)
    if not hdr: return None, None
    t = hdr[0]
    if t == 0x01:
        sz = recv_exact(sock, 1)
        if not sz: return None, None
        size = sz[0]
    elif t == 0x02:
        sz = recv_exact(sock, 4)
        if not sz: return None, None
        size = struct.unpack("<I", sz)[0]
    else:
        return None, None
    data = recv_exact(sock, size)
    if data is None: return None, None
    if t == 0x02:
        try: data = zlib.decompress(data, -15)
        except zlib.error: return None, None
    return t, data

def parse_outer(data: bytes):
    counter = cmd = None; inner = b""; extra = {}
    for fn, wire, val in decode_proto(data):
        if fn == 1 and wire == 0: counter = val
        elif fn == 2 and wire == 2:
            s = try_utf8(val)
            if s: cmd = s
        elif fn == 3 and wire == 2: inner = val
        elif fn == 4 and wire == 0: extra["code"] = val
        elif fn == 5 and wire == 2: extra["msg"] = val.decode(errors="replace")
    return counter, cmd, inner, extra

# ── Session helpers ────────────────────────────────────────────────────────────

def extract_session_token(data: bytes):
    text = "".join(chr(b) if 32 <= b < 127 else " " for b in data)
    m = re.findall(r"[0-9a-f]{7,}-[0-9a-f]{6,}-[0-9a-f]{6,}", text)
    return m[0] if m else None

def drain_until(sock, target_cmd: str, timeout: float = 10.0):
    """
    Read packets until target_cmd arrives.
    Returns inner bytes on success, or (None, code) on error/timeout.
    """
    sock.settimeout(timeout)
    while True:
        try:
            _, data = recv_packet(sock)
            if data is None: return None
            ctr, cmd, inner, extra = parse_outer(data)
            code = extra.get("code")
            if code:
                print(f"    [<<] {cmd!r}  code={code}  {extra.get('msg','')[:80]}")
                return code   # return the error code so caller can handle it
            else:
                print(f"    [<<] ctr={ctr}  cmd={cmd!r}  {len(inner)}B")
            if cmd == target_cmd: return inner
            if cmd == "ping":
                sock.sendall(make_ping_ack(ctr, inner))
        except socket.timeout:
            print(f"    [!] Timeout waiting for {target_cmd!r}"); return None

def drain_all(sock, timeout: float = 3.0, max_packets: int = 12):
    sock.settimeout(timeout); packets = []; ping_acked = set()
    while len(packets) < max_packets:
        try:
            _, data = recv_packet(sock)
            if data is None: break
            ctr, cmd, inner, extra = parse_outer(data)
            packets.append((ctr, cmd, inner, extra))
            if cmd == "ping" and ctr not in ping_acked:
                sock.sendall(make_ping_ack(ctr, inner))
                ping_acked.add(ctr)
        except socket.timeout: break
    return packets

# ── Packet builders ────────────────────────────────────────────────────────────

def make_login_packet(session_token: str, guid: str = GUID) -> bytes:
    pw = hashlib.md5((session_token + guid).encode()).hexdigest().encode()
    inner = ORIGINAL_INNER.replace(ORIGINAL_PASSWORD, pw)
    if inner == ORIGINAL_INNER:
        raise ValueError("Password substitution failed")
    # If using a non-default GUID, patch the GUID inside the login JSON too
    if guid != GUID:
        assert len(guid) == len(GUID), "GUID must be 36 chars"
        inner = inner.replace(ORIGINAL_GUID, guid.encode())
    return large_pkt(build_outer(2, "LOGIN", inner))

def make_ping(counter: int) -> bytes:
    ts_submsg = b"\x08" + enc_varint(int(time.time() * 1000))
    fingerprint = "36455C3E36075A6545181B460AF344FF1DCD053F"
    net_inner = enc_str(1, "net_data") + enc_str(2, fingerprint)
    inner = enc_bytes(1, ts_submsg) + enc_bytes(2, net_inner)
    return small_pkt(build_outer(counter, "ping", inner))

def make_ping_ack(server_counter: int, server_inner: bytes) -> bytes:
    server_ts = b""
    for fn, wire, val in decode_proto(server_inner):
        if fn == 1 and wire == 2: server_ts = val; break
    our_ts = b"\x08" + enc_varint(int(time.time() * 1000))
    ack_inner = enc_bytes(1, server_ts) + enc_bytes(2, our_ts)
    return small_pkt(build_outer(server_counter, "ping", ack_inner))

def make_brawler_start(counter: int) -> bytes:
    outer = build_outer(counter, "brawler_start")
    return small_pkt(outer) if len(outer) <= 255 else large_pkt(outer)

def extract_match_data(server_fight_inner: bytes) -> bytes:
    """
    The server's brawler_start inner has:
      f[1] = match data (opponent info, fight config) — this is what we echo back
      f[2] = seed/timestamp
      f[3] = empty (x2)
    We must echo ONLY the content of f[1], not the full inner.
    Verified against 4 captured sessions: user f[1] == server inner f[1] content exactly.
    """
    for fn, wire, val in decode_proto(server_fight_inner):
        if fn == 1 and wire == 2:
            return val
    return server_fight_inner  # fallback: send full inner (shouldn't happen)

def make_brawler_finish(counter: int, server_inner: bytes) -> bytes:
    """
    Build brawler_finish packet.
    server_inner = the inner payload from the server's brawler_start response.
    We echo server_inner's f[1] content (match data) as our f[1] — the fingerprint.
    """
    match_data = extract_match_data(server_inner)
    inner  = enc_bytes(1, match_data)              # f[1] echo: match data from server's f[1]
    inner += enc_varint_field(2, 1)               # f[2] result = win
    inner += enc_varint_field(3, 2)               # f[3] wonRounds = 2
    inner += enc_bytes(4, bytes.fromhex("08031001"))  # f[4] round results
    inner += enc_bytes(4, bytes.fromhex("08041002"))
    inner += enc_bytes(4, bytes.fromhex("08051003"))
    inner += enc_bytes(4, bytes.fromhex("08061002"))
    inner += enc_bytes(4, bytes.fromhex("0807"))
    inner += enc_varint_field(5, 2)               # f[5] = 2
    inner += enc_bytes(6, _BRAWLER_ITEMS)         # f[6] items
    inner += enc_bytes(7, _BRAWLER_STATS)         # f[7] stats
    outer = build_outer(counter, "brawler_finish", inner)
    return large_pkt(outer) if len(outer) > 200 else small_pkt(outer)

# ── Connection + login ─────────────────────────────────────────────────────────

def connect_and_login(host: str, guid: str = GUID):
    """Open socket, handshake, login. Returns (sock, counter, session_id) or raises."""
    print(f"  [*] Connecting to {host}:{PORT} ...")
    sock = socket.create_connection((host, PORT), timeout=12)
    sock.settimeout(10)

    # Handshake
    sock.sendall(HANDSHAKE_TX)
    _, raw_hs = recv_packet(sock)
    if raw_hs is None:
        sock.close(); raise ConnectionError("No handshake response")
    session_token = extract_session_token(raw_hs)
    if not session_token:
        sock.close(); raise ConnectionError(f"No session token in: {raw_hs.hex()[:40]}")
    print(f"  [+] Session token: {session_token}")

    # Login
    pw = hashlib.md5((session_token + guid).encode()).hexdigest()
    print(f"  [+] GUID         : {guid}")
    print(f"  [+] MD5 password : {pw}")
    sock.sendall(make_login_packet(session_token, guid))

    session_id = 0
    join_inner = drain_until(sock, "join_zone", timeout=12)
    if join_inner is None or isinstance(join_inner, int):
        code = join_inner if isinstance(join_inner, int) else 0
        sock.close(); raise ConnectionError(f"Login rejected (code={code}) — check GUID/credentials")
    for fn, wire, val in decode_proto(join_inner):
        if fn == 1 and wire == 0:
            session_id = val; break
    print(f"  [+] Logged in — session_id={session_id}")

    # Initial ping
    counter = 3
    sock.sendall(make_ping(counter)); counter += 1
    drain_all(sock, timeout=3)

    return sock, counter, session_id

# ── Fight inner cache (survives reconnects) ────────────────────────────────────

def save_fight_inner(data: bytes) -> None:
    """Persist the server's brawler_start inner so we can clear a stuck brawler on reconnect."""
    try:
        with open(FIGHT_INNER_CACHE, "wb") as f:
            f.write(data)
    except Exception:
        pass

def load_fight_inner() -> bytes | None:
    """Load cached fight inner, or None if not present."""
    try:
        with open(FIGHT_INNER_CACHE, "rb") as f:
            return f.read()
    except Exception:
        return None

def clear_fight_inner() -> None:
    try: os.remove(FIGHT_INNER_CACHE)
    except Exception: pass

# ── Brawler win loop ───────────────────────────────────────────────────────────

def try_clear_stuck_brawler(sock, counter: int, cached_inner: bytes) -> tuple[bool, int]:
    """
    If the server says brawler already started, try to complete it using
    the cached fight inner from the previous session. Returns (cleared, counter).
    """
    print(f"  [~] Attempting to clear stuck brawler with cached inner ({len(cached_inner)}B)...")
    sock.sendall(make_brawler_finish(counter, cached_inner))
    print(f"  [>>] brawler_finish (clear attempt)  ctr={counter}")
    counter += 1

    result = drain_until(sock, "brawler_finish", timeout=10)
    if isinstance(result, bytes):
        print(f"  [+] Stuck brawler cleared successfully!")
        clear_fight_inner()
        return True, counter
    else:
        print(f"  [!] Could not clear stuck brawler (result={result})")
        return False, counter

def run_brawler_session(sock, counter: int, wins_so_far: int, target: int) -> tuple[int, int]:
    """
    Run as many brawler wins as possible on this connection.
    Saves server fight inner before each finish so reconnects can clear stuck state.
    Returns (wins_so_far, counter).
    """
    while wins_so_far < target:
        # ── brawler_start ──────────────────────────────────────────
        sock.sendall(make_brawler_start(counter))
        print(f"  [>>] brawler_start  ctr={counter}")
        counter += 1

        result = drain_until(sock, "brawler_start", timeout=10)

        if isinstance(result, int):
            # Error code — check if it's "already started"
            if result == 50003:
                cached = load_fight_inner()
                if cached:
                    cleared, counter = try_clear_stuck_brawler(sock, counter, cached)
                    if cleared:
                        continue   # retry brawler_start
                print("  [!] Stuck brawler — session dropped (will retry)")
                return wins_so_far, counter
            print(f"  [!] brawler_start error {result} — session dropped")
            return wins_so_far, counter

        if result is None:
            print("  [!] No brawler_start response — session dropped")
            return wins_so_far, counter

        server_fight_inner = result   # bytes from server
        save_fight_inner(server_fight_inner)  # persist for crash recovery

        # ── brawler_finish ─────────────────────────────────────────
        sock.sendall(make_brawler_finish(counter, server_fight_inner))
        print(f"  [>>] brawler_finish ctr={counter}  (echo {len(server_fight_inner)}B)")
        counter += 1

        result = drain_until(sock, "brawler_finish", timeout=12)

        if not isinstance(result, bytes):
            print(f"  [!] brawler_finish failed (result={result}) — session dropped")
            # Inner is still cached — reconnect will clear it
            return wins_so_far, counter

        # Win confirmed — clear cache
        clear_fight_inner()
        wins_so_far += 1
        print(f"\n  ✓  WIN #{wins_so_far} / {target}\n")

        if wins_so_far < target:
            time.sleep(WIN_DELAY)
            sock.sendall(make_ping(counter)); counter += 1
            drain_all(sock, timeout=2)

    return wins_so_far, counter

# ── Main ───────────────────────────────────────────────────────────────────────

def main():
    global TARGET_WINS

    print("╔══════════════════════════════════════════╗")
    print("║  SF3 Duel Hijack — Auto Brawler Win Loop ║")
    print("╚══════════════════════════════════════════╝\n")

    print(f"  [+] Using GUID: {GUID}")
    cached = load_fight_inner()
    if cached:
        print(f"  [!] Found cached fight inner ({len(cached)}B) — will clear stuck brawler on connect.\n")

    try:
        raw = input(f"Target wins [{TARGET_WINS}]: ").strip()
        if raw: TARGET_WINS = int(raw)
    except (ValueError, EOFError): pass

    print(f"\n[*] Targeting {TARGET_WINS} wins. Will auto-relog on session drop.\n")

    wins = 0
    attempt = 0
    stuck_wait = False

    while wins < TARGET_WINS:
        attempt += 1
        print(f"{'─'*50}")
        print(f"[Session {attempt}]  wins so far: {wins}/{TARGET_WINS}")
        print(f"{'─'*50}")

        if stuck_wait:
            print(f"  [~] Waiting {STUCK_CLEAR_DELAY}s for server brawler timeout...")
            time.sleep(STUCK_CLEAR_DELAY)
            stuck_wait = False

        sock = None
        connected = False

        for host in HOSTS:
            try:
                sock, counter, session_id = connect_and_login(host, GUID)
                connected = True
                break
            except Exception as e:
                print(f"  [!] {host} failed: {e}")
                if sock:
                    try: sock.close()
                    except: pass
                    sock = None

        if not connected:
            print(f"  [!] All hosts failed — retrying in {RETRY_DELAY}s ...")
            time.sleep(RETRY_DELAY)
            continue

        prev_wins = wins
        try:
            wins, counter = run_brawler_session(sock, counter, wins, TARGET_WINS)
        except Exception as e:
            print(f"  [!] Session error: {e}")
        finally:
            try: sock.close()
            except: pass

        if wins < TARGET_WINS:
            no_progress = (wins == prev_wins)
            cached_still = load_fight_inner()
            if no_progress and cached_still:
                stuck_wait = True
            print(f"\n  [*] Session ended at {wins} wins — reconnecting in {RETRY_DELAY}s ...\n")
            time.sleep(RETRY_DELAY)

    print(f"\n{'═'*50}")
    print(f"  Done! Reached {wins} brawler wins.")
    print(f"{'═'*50}")

if __name__ == "__main__":
    main()
