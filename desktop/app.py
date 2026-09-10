import libtorrent as lt
import time
import os
import sys
import json
import threading
import webbrowser
import uuid
import urllib.request
import urllib.parse
from http.server import HTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse, parse_qs

# ---- 配置 ----
WEB_PORT = 8888

# 根据是否打包为 exe 决定下载目录和资源路径
if getattr(sys, 'frozen', False):
    BASE_DIR = os.path.dirname(sys.executable)
else:
    BASE_DIR = os.path.dirname(os.path.abspath(__file__))

# 任务状态 / 续传数据目录（.torrent 副本、fastresume、tasks.json、config.json）
STATE_DIR = os.path.join(BASE_DIR, ".bt_downloader")
os.makedirs(STATE_DIR, exist_ok=True)
TASKS_FILE = os.path.join(STATE_DIR, "tasks.json")
CONFIG_FILE = os.path.join(STATE_DIR, "config.json")


def _load_config():
    try:
        with open(CONFIG_FILE, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {}


def _save_config(cfg):
    try:
        tmp = CONFIG_FILE + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(cfg, f, ensure_ascii=False, indent=1)
        os.replace(tmp, CONFIG_FILE)
    except Exception as e:
        print("保存配置失败:", e, flush=True)


_config = _load_config()
DOWNLOAD_DIR = _config.get("download_dir") or os.path.join(BASE_DIR, "downloads")
try:
    os.makedirs(DOWNLOAD_DIR, exist_ok=True)
except Exception:
    DOWNLOAD_DIR = os.path.join(BASE_DIR, "downloads")
    os.makedirs(DOWNLOAD_DIR, exist_ok=True)


def set_download_dir(path):
    """切换下载目录（只影响之后的新任务），返回最终绝对路径"""
    global DOWNLOAD_DIR
    path = os.path.abspath(os.path.expanduser(path.strip().strip('"')))
    os.makedirs(path, exist_ok=True)
    # 验证可写
    probe = os.path.join(path, ".write_test")
    with open(probe, "w") as f:
        f.write("ok")
    os.remove(probe)
    DOWNLOAD_DIR = path
    _config["download_dir"] = path
    _save_config(_config)
    print(f"下载目录已切换: {path}", flush=True)
    return path


def pick_directory_dialog():
    """弹出系统文件夹选择对话框（tkinter），返回路径或 None"""
    try:
        import tkinter as tk
        from tkinter import filedialog
        result = {"path": None}

        def _dialog():
            root = tk.Tk()
            root.withdraw()
            root.attributes("-topmost", True)
            try:
                init = DOWNLOAD_DIR if os.path.isdir(DOWNLOAD_DIR) else BASE_DIR
                p = filedialog.askdirectory(initialdir=init, title="选择下载目录")
                if p:
                    result["path"] = p
            finally:
                root.destroy()

        # tkinter 在子线程运行：Windows 下可行
        t = threading.Thread(target=_dialog, daemon=True)
        t.start()
        t.join(timeout=300)
        return result["path"]
    except Exception as e:
        print("打开目录选择对话框失败:", e, flush=True)
        return None


# 加载 HTML 页面（与 Android 端共用同一份 UI）
def load_html_page():
    meipass = getattr(sys, "_MEIPASS", "")
    candidates = []
    if meipass:
        candidates.append(os.path.join(meipass, "web", "index.html"))
        candidates.append(os.path.join(meipass, "index.html"))
    candidates += [
        os.path.join(BASE_DIR, "index.html"),
        os.path.join(BASE_DIR, "web", "index.html"),
        os.path.join(BASE_DIR, "assets", "web", "index.html"),
        os.path.join(BASE_DIR, "..", "android", "app", "src", "main", "assets", "web", "index.html"),
    ]
    for p in candidates:
        abs_p = os.path.abspath(p) if not os.path.isabs(p) else p
        if os.path.exists(abs_p):
            with open(abs_p, "r", encoding="utf-8") as f:
                return f.read()
    return "<!DOCTYPE html><html><head><meta charset='UTF-8'><title>BT 下载器</title></head><body><p>页面加载失败</p></body></html>"


HTML_PAGE = load_html_page()

# ---- 全局状态 ----
status_lock = threading.Lock()
downloads = {}  # id -> {type, ...}

ses = None

STATE_STR = ["queued", "checking", "downloading metadata",
             "downloading", "finished", "seeding", "allocating",
             "checking fastresume"]


def get_state_name(s):
    try:
        return STATE_STR[s.state]
    except IndexError:
        return str(s.state)


# ============================================================
# 任务持久化
# ============================================================
def save_tasks_snapshot():
    """把当前任务列表写入 tasks.json（供重启后恢复）"""
    try:
        tasks = []
        with status_lock:
            for tid, d in downloads.items():
                sp = d.get("save_path", DOWNLOAD_DIR)
                if d.get("type") == "http":
                    obj = d["obj"]
                    tasks.append({
                        "id": tid, "type": "http",
                        "url": obj.url, "headers": obj.extra_headers,
                        "name": obj.name, "state": obj.state,
                        "save_path": sp,
                    })
                else:
                    tasks.append({
                        "id": tid, "type": "torrent",
                        "kind": d.get("kind", "magnet"),
                        "source": d.get("source", ""),
                        "torrent_file": d.get("torrent_file", ""),
                        "name": d.get("name", ""),
                        "save_path": sp,
                    })
        tmp = TASKS_FILE + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump({"tasks": tasks}, f, ensure_ascii=False, indent=1)
        os.replace(tmp, TASKS_FILE)
    except Exception as e:
        print("保存任务状态失败:", e, flush=True)


def _snapshot_loop():
    while True:
        time.sleep(5)
        save_tasks_snapshot()


# ============================================================
# HTTP/HTTPS 直链下载（支持 Range 断点续传）
# ============================================================
class HttpDownload:
    def __init__(self, url, save_path, extra_headers=None, name=None, task_id=None):
        self.url = url
        self.save_path = save_path
        self.extra_headers = extra_headers or {}
        self.task_id = task_id
        self.name = name or ""
        self.progress = 0.0
        self.state = "queued"       # queued / downloading / finished / error
        self.download_rate = 0.0
        self.total_download = 0
        self.total_size = 0
        self._stop_event = threading.Event()
        self._thread = None
        self._error = None

    def start(self):
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()

    def stop(self):
        self._stop_event.set()

    def _guess_filename(self, url, headers):
        cd = headers.get("Content-Disposition", "")
        if "filename=" in cd:
            fn = cd.split("filename=")[-1].strip("\"' ")
            # 处理 filename*=UTF-8''xxx
            if fn.startswith("UTF-8''"):
                fn = urllib.parse.unquote(fn[7:])
            if fn:
                return fn
        path = urlparse(url).path
        fn = os.path.basename(urllib.parse.unquote(path))
        if fn and "." in fn:
            return fn
        return "download.bin"

    def _open_request(self, resume_from=0):
        req_headers = {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                          "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        }
        req_headers.update(self.extra_headers)
        if resume_from > 0:
            req_headers["Range"] = f"bytes={resume_from}-"
        req = urllib.request.Request(self.url, headers=req_headers)
        return urllib.request.urlopen(req, timeout=30)

    def _run(self):
        resp = None
        try:
            # 1) 如果已有 .part，尝试断点续传
            resume_from = 0
            tmp_path = None
            if self.name:
                tmp_path = os.path.join(self.save_path, self.name + ".part")
                if os.path.exists(tmp_path):
                    resume_from = os.path.getsize(tmp_path)

            resp = self._open_request(resume_from)
            code = resp.getcode()

            # 2) 首次运行时从响应头拿文件名
            if not self.name:
                self.name = self._guess_filename(self.url, resp.headers)
                tmp_path = os.path.join(self.save_path, self.name + ".part")
                if os.path.exists(tmp_path) and resume_from == 0:
                    # 名字刚确定就发现残留 .part（上次没来得及记录名字）
                    resp.close()
                    resume_from = os.path.getsize(tmp_path)
                    resp = self._open_request(resume_from)
                    code = resp.getcode()

            out_path = os.path.join(self.save_path, self.name)

            # 3) 206 = 服务器支持续传；200 = 从头开始
            if code == 206 and resume_from > 0:
                cr = resp.headers.get("Content-Range", "")
                total = 0
                if "/" in cr:
                    try:
                        total = int(cr.rsplit("/", 1)[1])
                    except ValueError:
                        total = 0
                self.total_size = total or (resume_from + int(resp.headers.get("Content-Length", 0) or 0))
                self.total_download = resume_from
                mode = "ab"
                print(f"[HTTP] 断点续传: 从 {resume_from} 字节继续", flush=True)
            else:
                self.total_size = int(resp.headers.get("Content-Length", 0) or 0)
                self.total_download = 0
                resume_from = 0
                mode = "wb"

            save_tasks_snapshot()
            self.state = "downloading"

            last_time = time.time()
            last_bytes = self.total_download

            with open(tmp_path, mode) as f:
                while not self._stop_event.is_set():
                    chunk = resp.read(65536)
                    if not chunk:
                        break
                    f.write(chunk)
                    self.total_download += len(chunk)
                    now = time.time()
                    elapsed = now - last_time
                    if elapsed >= 0.5:
                        self.download_rate = (self.total_download - last_bytes) / elapsed
                        last_bytes = self.total_download
                        last_time = now
                    if self.total_size > 0:
                        self.progress = self.total_download / self.total_size

            if self._stop_event.is_set():
                # 被主动停止（删除任务）；.part 的清理由 remove_torrent 处理
                return

            os.replace(tmp_path, out_path)
            self.progress = 1.0
            self.download_rate = 0
            self.state = "finished"
            save_tasks_snapshot()
            print(f"[HTTP] 下载完成: {self.name}", flush=True)

        except Exception as e:
            self._error = str(e)
            self.state = "error"
            print(f"[HTTP] 下载出错（.part 已保留，重启可续传）: {e}", flush=True)
        finally:
            if resp is not None:
                try:
                    resp.close()
                except Exception:
                    pass


# ============================================================
# 添加任务
# ============================================================
def add_torrent_magnet(magnet, extra_headers=None, save_path=None):
    """磁力链接 / HTTP(S) 直链 统一入口"""
    sp = save_path or DOWNLOAD_DIR
    os.makedirs(sp, exist_ok=True)
    url_lower = magnet.strip().lower()
    if url_lower.startswith("http://") or url_lower.startswith("https://"):
        return add_http_url(magnet.strip(), extra_headers, sp)

    if not url_lower.startswith("magnet:"):
        raise ValueError("不支持的链接类型，请使用 magnet: 开头的磁力链接或 http(s):// 直链")

    params = lt.parse_magnet_uri(magnet)
    params.save_path = sp
    handle = ses.add_torrent(params)
    tid = str(uuid.uuid4())
    name = params.name if params.name else f"magnet_{str(params.info_hash)}"
    with status_lock:
        downloads[tid] = {"type": "torrent", "handle": handle, "name": name,
                          "total_size": 0, "kind": "magnet", "source": magnet,
                          "torrent_file": "", "save_path": sp, "added_at": time.time()}
    save_tasks_snapshot()
    _request_resume(handle)
    return tid


def add_http_url(url, extra_headers=None, save_path=None):
    sp = save_path or DOWNLOAD_DIR
    os.makedirs(sp, exist_ok=True)
    tid = str(uuid.uuid4())
    dl = HttpDownload(url, sp, extra_headers, task_id=tid)
    with status_lock:
        downloads[tid] = {"type": "http", "obj": dl, "name": "",
                          "total_size": 0, "save_path": sp, "added_at": time.time()}
    save_tasks_snapshot()
    dl.start()
    return tid


def add_torrent_file(data, save_path=None):
    """通过 .torrent 文件内容添加下载（.torrent 会复制到状态目录以便重启恢复）"""
    sp = save_path or DOWNLOAD_DIR
    os.makedirs(sp, exist_ok=True)
    info = lt.torrent_info(lt.bdecode(data))
    params = lt.add_torrent_params()
    params.ti = info
    params.save_path = sp
    tid = str(uuid.uuid4())
    torrent_path = os.path.join(STATE_DIR, tid + ".torrent")
    with open(torrent_path, "wb") as f:
        f.write(data)
    fastresume = os.path.join(STATE_DIR, tid + ".fastresume")
    if os.path.exists(fastresume):
        try:
            with open(fastresume, "rb") as f:
                params = lt.read_resume_data(f.read())
            params.ti = info
            params.save_path = sp
        except Exception:
            params = lt.add_torrent_params()
            params.ti = info
            params.save_path = sp
    handle = ses.add_torrent(params)
    with status_lock:
        downloads[tid] = {"type": "torrent", "handle": handle, "name": info.name(),
                          "total_size": info.total_size(), "kind": "file",
                          "source": "", "torrent_file": torrent_path,
                          "save_path": sp, "added_at": time.time()}
    save_tasks_snapshot()
    _request_resume(handle)
    return tid


def parse_headers_text(text):
    """解析 "Key: Value\nKey2: Value2" 格式"""
    result = {}
    if not text:
        return result
    for line in text.split("\n"):
        line = line.strip()
        idx = line.find(":")
        if idx > 0:
            result[line[:idx].strip()] = line[idx + 1:].strip()
    return result


def remove_torrent(tid):
    with status_lock:
        d = downloads.pop(tid, None)
    if d is None:
        return False
    try:
        if d.get("type") == "http":
            obj = d["obj"]
            obj.stop()
            # 清理未完成的 .part（已完成的最终文件保留）
            sp = d.get("save_path", DOWNLOAD_DIR)
            if obj.name:
                part = os.path.join(sp, obj.name + ".part")
                if os.path.exists(part):
                    os.remove(part)
        else:
            ses.remove_torrent(d["handle"])
            fr = os.path.join(STATE_DIR, tid + ".fastresume")
            if os.path.exists(fr):
                os.remove(fr)
            tf = d.get("torrent_file", "")
            if tf and os.path.exists(tf):
                os.remove(tf)
    except Exception as e:
        print("删除任务清理失败:", e, flush=True)
    save_tasks_snapshot()
    return True


# ============================================================
# BT 续传数据（fastresume）
# ============================================================
def _request_resume(handle):
    try:
        handle.save_resume_data()
    except Exception:
        pass


def _resume_data_loop():
    """监听 save_resume_data 告警，把 fastresume 落盘；每 30 秒主动请求一次"""
    try:
        ses.set_alert_mask(lt.alert.category_t.all_categories)
    except Exception:
        pass
    last_request = 0
    while True:
        time.sleep(2)
        try:
            for a in ses.pop_alerts():
                if isinstance(a, lt.save_resume_data_alert):
                    try:
                        ah = a.handle.info_hash()
                        buf = lt.bencode(a.resume_data)
                        with status_lock:
                            items = list(downloads.items())
                        for tid, d in items:
                            if d.get("type") != "torrent":
                                continue
                            try:
                                if d["handle"].info_hash() == ah:
                                    with open(os.path.join(STATE_DIR, tid + ".fastresume"), "wb") as f:
                                        f.write(buf)
                                    break
                            except Exception:
                                continue
                    except Exception as e:
                        print("写入 fastresume 失败:", e, flush=True)
        except Exception:
            pass

        if time.time() - last_request > 30:
            last_request = time.time()
            with status_lock:
                items = list(downloads.values())
            for d in items:
                if d.get("type") == "torrent":
                    _request_resume(d["handle"])


# ============================================================
# 重启后恢复任务
# ============================================================
def restore_tasks():
    if not os.path.exists(TASKS_FILE):
        return
    try:
        with open(TASKS_FILE, "r", encoding="utf-8") as f:
            data = json.load(f)
    except Exception as e:
        print("读取 tasks.json 失败:", e, flush=True)
        return

    for t in data.get("tasks", []):
        try:
            tid = t.get("id") or str(uuid.uuid4())
            # 任务各自的保存目录（旧记录没有则用当前默认目录）
            sp = t.get("save_path") or DOWNLOAD_DIR
            try:
                os.makedirs(sp, exist_ok=True)
            except Exception:
                sp = DOWNLOAD_DIR
                os.makedirs(sp, exist_ok=True)

            if t.get("type") == "http":
                name = t.get("name", "")
                final = os.path.join(sp, name) if name else ""
                if name and os.path.exists(final) and not os.path.exists(final + ".part"):
                    # 已完成：直接显示完成状态，不重新下载
                    dl = HttpDownload(t["url"], sp, t.get("headers"))
                    dl.name = name
                    dl.state = "finished"
                    dl.progress = 1.0
                    dl.total_size = os.path.getsize(final)
                    dl.total_download = dl.total_size
                    with status_lock:
                        downloads[tid] = {"type": "http", "obj": dl, "name": name,
                                          "total_size": dl.total_size, "save_path": sp,
                                          "added_at": time.time()}
                else:
                    dl = HttpDownload(t["url"], sp, t.get("headers"),
                                      name=name or None, task_id=tid)
                    with status_lock:
                        downloads[tid] = {"type": "http", "obj": dl, "name": name,
                                          "total_size": 0, "save_path": sp,
                                          "added_at": time.time()}
                    dl.start()
                print(f"[恢复] HTTP 任务: {name or t['url'][:60]} -> {sp}", flush=True)

            elif t.get("type") == "torrent":
                fastresume = os.path.join(STATE_DIR, tid + ".fastresume")
                fr_data = None
                if os.path.exists(fastresume):
                    with open(fastresume, "rb") as f:
                        fr_data = f.read()

                # .torrent 文件任务需要种子信息（fastresume 不含完整元数据）
                info = None
                if t.get("kind") == "file" and t.get("torrent_file") and os.path.exists(t["torrent_file"]):
                    with open(t["torrent_file"], "rb") as f:
                        file_data = f.read()
                    info = lt.torrent_info(lt.bdecode(file_data))

                if fr_data:
                    # 官方推荐：read_resume_data 直接还原 add_torrent_params（跳过哈希校验）
                    params = lt.read_resume_data(fr_data)
                    params.save_path = sp
                    if info is not None:
                        params.ti = info
                    handle = ses.add_torrent(params)
                    name = t.get("name") or (info.name() if info else "") or "torrent"
                elif info is not None:
                    params = lt.add_torrent_params()
                    params.ti = info
                    params.save_path = sp
                    handle = ses.add_torrent(params)
                    name = t.get("name") or info.name()
                else:
                    magnet = t.get("source", "")
                    if not magnet:
                        continue
                    params = lt.parse_magnet_uri(magnet)
                    params.save_path = sp
                    handle = ses.add_torrent(params)
                    name = t.get("name") or (params.name or "")

                with status_lock:
                    downloads[tid] = {
                        "type": "torrent", "handle": handle, "name": name,
                        "total_size": info.total_size() if info else 0,
                        "kind": t.get("kind", "magnet"),
                        "source": t.get("source", ""),
                        "torrent_file": t.get("torrent_file", ""),
                        "save_path": sp,
                        "added_at": time.time(),
                    }
                _request_resume(handle)
                print(f"[恢复] BT 任务: {name[:60]}", flush=True)
        except Exception as e:
            print(f"[恢复] 任务失败（跳过）: {e}", flush=True)


# ============================================================
# 状态查询
# ============================================================
def get_downloads_info():
    result = []
    with status_lock:
        items = list(downloads.items())
    for tid, d in items:
        if d.get("type") == "http":
            obj = d["obj"]
            result.append({
                "id": tid,
                "name": obj.name or d.get("name") or "(连接中...)",
                "progress": obj.progress,
                "state": obj.state,
                "download_rate": obj.download_rate,
                "upload_rate": 0,
                "num_seeds": 0,
                "num_peers": 0,
                "total_download": obj.total_download,
                "total_size": obj.total_size,
            })
        else:
            h = d["handle"]
            try:
                s = h.status()
                result.append({
                    "id": tid,
                    "name": s.name or d.get("name", ""),
                    "progress": s.progress,
                    "state": get_state_name(s),
                    "download_rate": s.download_rate,
                    "upload_rate": s.upload_rate,
                    "num_seeds": s.num_seeds,
                    "num_peers": s.num_peers,
                    "total_download": s.total_download,
                    "total_size": s.total_wanted or d.get("total_size", 0),
                })
            except Exception:
                result.append({
                    "id": tid, "name": d.get("name", ""), "progress": 0,
                    "state": "error", "download_rate": 0, "upload_rate": 0,
                    "num_seeds": 0, "num_peers": 0, "total_download": 0,
                    "total_size": d.get("total_size", 0),
                })
    return {"downloads": result}


# ============================================================
# HTTP 服务
# ============================================================
class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path in ("/", "/index.html"):
            body = HTML_PAGE.encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        elif self.path == "/api/list":
            body = json.dumps(get_downloads_info(), ensure_ascii=False).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        elif self.path == "/api/config":
            body = json.dumps({"download_dir": DOWNLOAD_DIR}, ensure_ascii=False).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        else:
            self.send_response(404)
            self.end_headers()

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length)

        if self.path == "/api/add":
            try:
                data = json.loads(raw.decode("utf-8"))
                url = data.get("magnet") or data.get("url") or ""
                if not url:
                    raise ValueError("请输入磁力链接或下载地址")
                extra_headers = {}
                raw_headers = data.get("headers", "")
                if raw_headers:
                    extra_headers.update(parse_headers_text(raw_headers))
                cookies = data.get("cookies", "")
                if cookies:
                    extra_headers["Cookie"] = cookies
                ua = data.get("userAgent", "")
                if ua:
                    extra_headers["User-Agent"] = ua
                tid = add_torrent_magnet(url, extra_headers if extra_headers else None)
                resp = json.dumps({"ok": True, "id": tid}).encode("utf-8")
            except Exception as e:
                resp = json.dumps({"ok": False, "error": str(e)}, ensure_ascii=False).encode("utf-8")
            self._json(resp)

        elif self.path == "/api/upload":
            try:
                ctype = self.headers.get("Content-Type", "")
                if "multipart/form-data" in ctype:
                    boundary = ctype.split("boundary=")[1].encode()
                    parts = raw.split(b"--" + boundary)
                    file_data = None
                    for part in parts:
                        if b'filename="' in part:
                            idx = part.find(b"\r\n\r\n")
                            if idx != -1:
                                file_data = part[idx + 4:]
                                if file_data.endswith(b"\r\n"):
                                    file_data = file_data[:-2]
                                break
                    if file_data:
                        tid = add_torrent_file(file_data)
                        resp = json.dumps({"ok": True, "id": tid}).encode("utf-8")
                    else:
                        resp = json.dumps({"ok": False, "error": "未找到文件"}, ensure_ascii=False).encode("utf-8")
                else:
                    resp = json.dumps({"ok": False, "error": "非文件上传请求"}, ensure_ascii=False).encode("utf-8")
            except Exception as e:
                resp = json.dumps({"ok": False, "error": str(e)}, ensure_ascii=False).encode("utf-8")
            self._json(resp)

        elif self.path.startswith("/api/remove"):
            qs = urlparse(self.path).query
            tid = parse_qs(qs).get("id", [""])[0]
            ok = remove_torrent(tid)
            resp = json.dumps({"ok": ok}).encode("utf-8")
            self._json(resp)

        elif self.path == "/api/pick-dir":
            try:
                picked = pick_directory_dialog()
                if not picked:
                    resp = json.dumps({"ok": False, "cancelled": True}, ensure_ascii=False).encode("utf-8")
                else:
                    path = set_download_dir(picked)
                    resp = json.dumps({"ok": True, "download_dir": path}, ensure_ascii=False).encode("utf-8")
            except Exception as e:
                resp = json.dumps({"ok": False, "error": str(e)}, ensure_ascii=False).encode("utf-8")
            self._json(resp)
        else:
            self.send_response(404)
            self.end_headers()

    def _json(self, resp):
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(resp)))
        self.end_headers()
        self.wfile.write(resp)

    def log_message(self, *args):
        pass


def run_web_server():
    server = HTTPServer(("127.0.0.1", WEB_PORT), Handler)
    print(f"Web UI: http://127.0.0.1:{WEB_PORT}", flush=True)
    server.serve_forever()


def main():
    global ses
    ses = lt.session()
    ses.listen_on(6881, 6891)
    try:
        ses.add_dht_router("router.bittorrent.com", 6881)
        ses.add_dht_router("dht.transmissionbt.com", 6881)
        ses.add_dht_router("router.utorrent.com", 6881)
    except Exception:
        pass

    # 恢复上次未完成的任务（断点续传）
    restore_tasks()

    # 后台线程：定时保存 fastresume + tasks.json
    threading.Thread(target=_resume_data_loop, daemon=True).start()
    threading.Thread(target=_snapshot_loop, daemon=True).start()

    t = threading.Thread(target=run_web_server, daemon=True)
    t.start()
    time.sleep(0.5)

    try:
        webbrowser.open(f"http://127.0.0.1:{WEB_PORT}")
    except Exception:
        pass

    print(f"下载目录: {DOWNLOAD_DIR}", flush=True)
    print(f"状态目录: {STATE_DIR}", flush=True)
    print("服务运行中，关闭软件后重新打开会自动续传...", flush=True)

    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        print("正在退出，保存进度...", flush=True)
        save_tasks_snapshot()


if __name__ == "__main__":
    main()
