"""
HTTP Proxy Server
Usage:
  python socks5_server.py                    # 端口 1080
  python socks5_server.py --port 1080
"""

import argparse
import socket
import threading
import logging
import sys
from datetime import datetime
from urllib.parse import urlparse

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
)
log = logging.getLogger("httpproxy")


class HttpProxyServer:

    def __init__(self, host: str, port: int):
        self.host = host
        self.port = port

    def start(self):
        server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind((self.host, self.port))
        server.listen(128)
        log.info(f"HTTP proxy listening on {self.host}:{self.port}")
        while True:
            client, addr = server.accept()
            threading.Thread(target=self.handle, args=(client, addr), daemon=True).start()

    def handle(self, client: socket.socket, addr: tuple):
        client_ip = addr[0]
        start_time = datetime.now()
        dst_info = ""
        success = False
        try:
            client.settimeout(60)
            request_line = self._read_line(client)
            if not request_line:
                log.info(f"[{client_ip}] 连接建立但未发送数据，已关闭")
                return

            parts = request_line.split(" ")
            if len(parts) < 3:
                return

            method = parts[0].upper()

            if method == "CONNECT":
                host_port = parts[1]
                if ":" not in host_port:
                    return
                host, dst_port_str = host_port.rsplit(":", 1)
                port = int(dst_port_str)
                dst_info = f"{host}:{port}"

                self._skip_headers(client)

                log.info(f"[{client_ip}] -> {dst_info} [{start_time.strftime('%H:%M:%S')}]")

                remote = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                remote.settimeout(30)
                try:
                    remote.connect((host, port))
                except Exception as e:
                    log.warning(f"[{client_ip}] -> {dst_info} 连接失败: {e}")
                    self._send_http_error(client, 502, "Bad Gateway")
                    return

                client.sendall(b"HTTP/1.1 200 Connection established\r\n\r\n")
                success = True
                self._relay(client, remote)
            else:
                raw_url = parts[1]
                parsed = urlparse(raw_url)
                host = parsed.hostname
                port = parsed.port or 80
                path = parsed.path if parsed.path else "/"
                if parsed.query:
                    path += "?" + parsed.query
                dst_info = f"{host}:{port}"

                log.info(f"[{client_ip}] -> {method} {raw_url} [{start_time.strftime('%H:%M:%S')}]")

                remote = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                remote.settimeout(30)
                try:
                    remote.connect((host, port))
                except Exception as e:
                    log.warning(f"[{client_ip}] -> {dst_info} 连接失败: {e}")
                    self._send_http_error(client, 502, "Bad Gateway")
                    return

                success = True
                rewritten_first = f"{method} {path} HTTP/1.1\r\n".encode()

                rest = b""
                while True:
                    line = self._read_line_bytes(client)
                    if line is None or line == b"\r\n":
                        break
                    line_str = line.decode("utf-8", errors="replace")
                    if line_str.lower().startswith("proxy-"):
                        continue
                    rest += line

                remote.sendall(rewritten_first + rest)
                self._relay(client, remote)
        except Exception as e:
            log.debug(f"Error: {e}")
        finally:
            end_time = datetime.now()
            try:
                client.close()
            except Exception:
                pass
            if dst_info:
                duration = (end_time - start_time).total_seconds()
                status = "已完成" if success else "失败"
                log.info(
                    f"[{client_ip}] -> {dst_info} {status}  "
                    f"开始:{start_time.strftime('%H:%M:%S')} "
                    f"结束:{end_time.strftime('%H:%M:%S')} "
                    f"时长:{duration:.1f}s"
                )

    def _skip_headers(self, client: socket.socket):
        while True:
            line = self._read_line_bytes(client)
            if line is None or line == b"\r\n":
                break

    def _relay(self, client: socket.socket, remote: socket.socket):
        remote.settimeout(None)
        client.settimeout(None)
        pair = {client: remote, remote: client}
        while True:
            r, _, _ = select.select([client, remote], [], [])
            for s in r:
                data = s.recv(65536)
                if not data:
                    return
                pair[s].sendall(data)

    def _send_http_error(self, client: socket.socket, code: int, message: str):
        try:
            body = f"<h1>{code} {message}</h1>"
            resp = (f"HTTP/1.1 {code} {message}\r\n"
                    f"Content-Type: text/html\r\n"
                    f"Content-Length: {len(body)}\r\n"
                    f"Connection: close\r\n\r\n"
                    f"{body}")
            client.sendall(resp.encode())
        except Exception:
            pass

    @staticmethod
    def _read_line(sock: socket.socket) -> str:
        data = b""
        while True:
            ch = sock.recv(1)
            if not ch:
                return ""
            data += ch
            if data.endswith(b"\r\n"):
                return data[:-2].decode("utf-8", errors="replace")

    @staticmethod
    def _read_line_bytes(sock: socket.socket):
        data = b""
        while True:
            ch = sock.recv(1)
            if not ch:
                return None
            data += ch
            if data.endswith(b"\r\n"):
                return data


def main():
    parser = argparse.ArgumentParser(description="HTTP Proxy Server")
    parser.add_argument("--port", type=int, default=12325, help="Bind port (default: 12325)")
    parser.add_argument("--host", default="0.0.0.0", help="Bind address (default: 0.0.0.0)")
    args = parser.parse_args()

    try:
        HttpProxyServer(args.host, args.port).start()
    except PermissionError:
        print(f"Permission denied on port {args.port}. Try a higher port or run as admin.")
        sys.exit(1)
    except OSError as e:
        print(f"Failed to start: {e}")
        sys.exit(1)


if __name__ == "__main__":
    import select
    main()
