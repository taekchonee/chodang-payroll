"""이지포스 백오피스 정찰 스크립트.

브라우저 창을 띄우면 사장님이 직접 로그인하고 매출 화면을 조회합니다.
그동안 백오피스가 서버와 주고받는 데이터의 '형식'을 파일로 저장합니다.

- 로그인 요청은 통째로 저장하지 않습니다 (암호화된 아이디/비밀번호 포함).
- 쿠키, 세션 헤더는 저장하지 않습니다.
- 매출 데이터는 데이터 묶음(Dataset)마다 앞쪽 몇 줄만 저장합니다.
  (단, 백오피스 메뉴 목록처럼 화면 주소가 들어 있는 묶음은 전부 저장합니다.)

실행: python recon/recon.py
"""

from __future__ import annotations

import json
import os
import re
import sys
import xml.etree.ElementTree as ET
from datetime import datetime
from pathlib import Path

from playwright.sync_api import sync_playwright

START_URL = "https://smart.easypos.net/index.jsp"
HOST = "smart.easypos.net"
MAX_ROWS = 30  # 데이터 묶음마다 저장할 최대 줄 수

# 이런 이름이 들어간 요청/값은 저장하지 않습니다.
SENSITIVE = re.compile(r"(pw|passw|secur|rsa|token|session|cookie|auth|login|cert)", re.I)
# 이런 열 이름이 있는 묶음은 메뉴/화면 목록으로 보고 전부 저장합니다.
KEEP_ALL_COLS = re.compile(r"(url|pgm|xfdl|form_?id|scrn)", re.I)
STATIC_EXT = re.compile(r"\.(js|css|png|jpe?g|gif|svg|ico|woff2?|ttf|html?|xml\.js)(\?|$)", re.I)

OUT_DIR = Path(__file__).resolve().parent.parent / "recon_output"


def _strip_ns(tag: str) -> str:
    return tag.split("}", 1)[-1]


def summarize_nexacro_xml(text: str) -> dict | None:
    """Nexacro XML 응답을 요약합니다. XML이 아니면 None."""
    if "<Dataset" not in text and "<Parameters" not in text:
        return None
    try:
        root = ET.fromstring(text.lstrip("﻿").strip())
    except ET.ParseError:
        return None

    result: dict = {"format": "nexacro-xml", "parameters": {}, "datasets": []}
    for el in root.iter():
        tag = _strip_ns(el.tag)
        if tag == "Parameter":
            name = el.get("id", "")
            value = el.text or ""
            if SENSITIVE.search(name):
                value = "<가림>"
            result["parameters"][name] = value
        elif tag == "Dataset":
            ds_id = el.get("id", "")
            cols = []
            for c in el.iter():
                if _strip_ns(c.tag) == "Column":
                    cols.append({k: v for k, v in c.attrib.items() if k in ("id", "type", "size")})
            col_ids = [c.get("id", "") for c in cols]
            rows = []
            for r in el.iter():
                if _strip_ns(r.tag) != "Row":
                    continue
                row = {}
                for col in r:
                    if _strip_ns(col.tag) != "Col":
                        continue
                    cid = col.get("id", "")
                    row[cid] = "<가림>" if SENSITIVE.search(cid) else (col.text or "")
                rows.append(row)
            keep_all = any(KEEP_ALL_COLS.search(c) for c in col_ids)
            result["datasets"].append(
                {
                    "id": ds_id,
                    "columns": cols,
                    "row_count": len(rows),
                    "rows": rows if keep_all else rows[:MAX_ROWS],
                    "truncated": (not keep_all) and len(rows) > MAX_ROWS,
                }
            )
    return result


def summarize_ssv(text: str) -> dict | None:
    """Nexacro SSV(구분자 형식) 응답을 요약합니다."""
    if not text.startswith("SSV") and "\x1e" not in text:
        return None
    records = text.split("\x1e")
    kept: list[str] = []
    rows_in_ds = 0
    keep_all = False
    for rec in records:
        if rec.startswith("Dataset:"):
            rows_in_ds = 0
            keep_all = False
            kept.append(rec)
            continue
        if rec.startswith("_RowType_"):
            keep_all = bool(KEEP_ALL_COLS.search(rec))
            kept.append(rec)
            continue
        rows_in_ds += 1
        if keep_all or rows_in_ds <= MAX_ROWS:
            fields = rec.split("\x1f")
            kept.append("\x1f".join(fields))
    return {"format": "nexacro-ssv", "records": kept, "record_count": len(records)}


def summarize_body(text: str) -> dict:
    return (
        summarize_nexacro_xml(text)
        or summarize_ssv(text)
        or {"format": "raw", "head": text[:3000], "length": len(text)}
    )


def main() -> None:
    OUT_DIR.mkdir(exist_ok=True)
    out_path = OUT_DIR / f"capture_{datetime.now():%Y%m%d_%H%M%S}.json"
    capture: dict = {
        "captured_at": datetime.now().isoformat(timespec="seconds"),
        "screens_opened": [],
        "transactions": [],
        "downloads": [],
    }

    def save() -> None:
        out_path.write_text(json.dumps(capture, ensure_ascii=False, indent=2), encoding="utf-8")

    def on_request_finished(request) -> None:
        url = request.url
        if HOST not in url:
            return
        path = url.split(HOST, 1)[1]
        if path.endswith(".xfdl.js"):
            screen = path.rsplit("/easyposnx/", 1)[-1]
            if screen not in capture["screens_opened"]:
                capture["screens_opened"].append(screen)
                print(f"  화면 열림: {screen}")
                save()
            return
        if request.method != "POST" or STATIC_EXT.search(path):
            return

        body = request.post_data or ""
        if SENSITIVE.search(path) or re.search(r"secured(Username|Password)|RSA", body):
            capture["transactions"].append({"url": path, "skipped": "로그인 관련 요청이라 저장하지 않음"})
            print(f"  (로그인 요청은 건너뜀: {path})")
            save()
            return

        try:
            response = request.response()
            resp_text = response.text() if response else ""
            status = response.status if response else None
        except Exception as exc:  # 응답을 못 읽어도 요청 형식은 남깁니다.
            resp_text, status = "", f"응답 읽기 실패: {exc}"

        capture["transactions"].append(
            {
                "url": path,
                "status": status,
                "request_content_type": request.headers.get("content-type", ""),
                "request": summarize_body(body),
                "response": summarize_body(resp_text),
            }
        )
        print(f"  데이터 저장: {path}")
        save()

    def on_download(download) -> None:
        capture["downloads"].append({"url": download.url.split(HOST, 1)[-1], "file": download.suggested_filename})
        print(f"  다운로드 감지: {download.suggested_filename}")
        save()

    headless = os.environ.get("RECON_HEADLESS") == "1"  # 개발용 점검 옵션
    with sync_playwright() as p:
        launch_args = {"headless": headless}
        if os.environ.get("RECON_CHROMIUM"):
            launch_args["executable_path"] = os.environ["RECON_CHROMIUM"]
        browser = p.chromium.launch(**launch_args)
        context = browser.new_context(viewport={"width": 1400, "height": 900}, locale="ko-KR")
        context.on("requestfinished", on_request_finished)
        page = context.new_page()
        page.on("download", on_download)
        page.goto(START_URL)

        print()
        print("=" * 60)
        print(" 브라우저 창에서 직접 로그인해 주세요.")
        print(" 그다음 '매출' 메뉴 아래 화면들을 하나씩 열고,")
        print(" 날짜를 '지난 7일' 정도로 정해서 [조회] 버튼을 눌러 주세요.")
        print(" 가능하면 이런 화면을 꼭 열어 주세요:")
        print("   1) 영수증별 / 거래별 / 결제 상세 내역")
        print("   2) 메뉴별(상품별) 매출")
        print("   3) 시간대별 매출")
        print("   4) 일자별 매출")
        print(" [엑셀] 버튼이 있으면 한 번씩 눌러 보셔도 됩니다.")
        print(" 다 마치면 이 창에서 Enter 키를 누르세요.")
        print("=" * 60)
        if headless:
            page.wait_for_timeout(5000)
        else:
            try:
                input()
            except EOFError:
                pass
        save()
        browser.close()

    print(f"\n저장 완료: {out_path}")
    print(f"  열린 화면 {len(capture['screens_opened'])}개, 데이터 요청 {len(capture['transactions'])}개")


if __name__ == "__main__":
    sys.exit(main())
