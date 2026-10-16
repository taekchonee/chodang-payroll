import streamlit as st
import pandas as pd
from datetime import datetime, date, timedelta
import json
import os

DATA_FILE = "payroll_data.json"

st.set_page_config(page_title="초당예반 근태 관리", page_icon="🌱", layout="centered")

def load_data():
    if os.path.exists(DATA_FILE):
        try:
            with open(DATA_FILE, "r", encoding="utf-8") as f:
                return json.load(f)
        except:
            return {}
    return {}

def save_data(data):
    with open(DATA_FILE, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=4)

data = load_data()
st.title("🌱 초당예반 근태 기록 시스템")

mode = st.sidebar.radio("접속 모드", ["직원 근태 입력", "사장님 관리자 모드 (정산)"])
emp_list = list(data.keys())

if not emp_list and mode != "사장님 관리자 모드 (정산)":
    st.warning("등록된 직원이 없습니다. '사장님 관리자 모드'에서 먼저 직원을 등록해 주세요.")
    st.stop()

# 현재 날짜 기준 기본 정산월 자동 계산 (매월 4일 기준)
now = datetime.now()
if now.day >= 4:
    init_month = now.month + 1
    init_year = now.year
    if init_month > 12:
        init_month = 1
        init_year += 1
else:
    init_month = now.month
    init_year = now.year

years_list = [2026, 2027, 2028]
default_year_idx = years_list.index(init_year) if init_year in years_list else 0
default_month_idx = init_month - 1

if mode == "직원 근태 입력":
    st.subheader("📝 본인 근무시간 입력 (달력)")
    if not emp_list:
        st.stop()
    selected_emp = st.selectbox("본인의 이름을 선택하세요", emp_list, key="emp_select_main")
    emp_info = data[selected_emp]
    
    col_y, col_m = st.columns(2)
    with col_y:
        target_year = st.selectbox("정산 연도", years_list, index=default_year_idx, key="emp_y")
    with col_m:
        target_month = st.selectbox("정산 월", list(range(1, 13)), index=default_month_idx, key="emp_m")

    start_date = date(target_year - 1, 12, 4) if target_month == 1 else date(target_year, target_month - 1, 4)
    end_date = date(target_year, target_month, 3)
    st.write(f"**기록 기간:** {start_date.strftime('%Y.%m.%d')} - {end_date.strftime('%Y.%m.%d')}")

    if "records" not in emp_info:
        emp_info["records"] = {}

    all_dates = [start_date + timedelta(days=i) for i in range((end_date - start_date).days + 1)]
    weeks, cur_w = [], [None] * start_date.weekday()
    for d in all_dates:
        cur_w.append(d)
        if len(cur_w) == 7:
            weeks.append(cur_w)
            cur_w = []
    if cur_w:
        while len(cur_w) < 7: cur_w.append(None)
        weeks.append(cur_w)

    daily_inputs = {}
    with st.form("employee_record_form"):
        st.markdown("---")
        st.write("휴무이거나 결근한 날은 **0**으로 입력해 주세요.")
        
        hdr = st.columns(7)
        for idx, (lbl, col_color) in enumerate(zip(["월","화","수","목","금","토","일"], ["inherit","inherit","inherit","inherit","inherit","#3b82f6","#ef4444"])):
            hdr[idx].markdown(f"<div style='text-align: center; font-weight: bold; color: {col_color};'>{lbl}</div>", unsafe_allow_html=True)
        st.markdown("<hr style='margin:5px 0;'>", unsafe_allow_html=True)

        for w_idx, week in enumerate(weeks):
            r_cols = st.columns(7)
            for i, d in enumerate(week):
                with r_cols[i]:
                    if d:
                        d_str = d.strftime("%Y-%m-%d")
                        c_col = "#3b82f6" if i == 5 else ("#ef4444" if i == 6 else "inherit")
                        st.markdown(f"<div style='text-align: center; font-size: 14px; color: {c_col}; font-weight: bold;'>{d.month}/{d.day}</div>", unsafe_allow_html=True)
                        
                        def_val = emp_info["records"].get(d_str, "")
                        if def_val == "" and d.weekday() in emp_info["work_days"]:
                            def_val = emp_info["daily_hours"]
                        elif def_val == "":
                            def_val = 0.0

                        daily_inputs[d_str] = st.number_input(f"일자_{d_str}", value=float(def_val), step=0.5, format="%.1f", label_visibility="collapsed", key=f"emp_d_{w_idx}_{i}_{d_str}")
                    else:
                        st.write("")
            st.markdown("<div style='margin-bottom: 10px;'></div>", unsafe_allow_html=True)
            
        if st.form_submit_button("💾 내 근무 기록 저장하기", use_container_width=True):
            emp_info["records"].update(daily_inputs)
            save_data(data)
            st.success(f"[{selected_emp}] 님의 근무 기록이 저장되었습니다!")

    # 직원 화면용: 시급/급여 제외하고 총 누적 근무시간만 표시
    tmp_recs = emp_info.get("records", {}).copy()
    tmp_recs.update(daily_inputs)
    
    t_hrs = 0
    curr = start_date
    while curr <= end_date:
        d_str = curr.strftime("%Y-%m-%d")
        t_hrs += tmp_recs.get(d_str, 0)
        curr += timedelta(days=1)

    st.markdown("---")
    st.markdown("### ⏱️ 이번 달 나의 누적 근무시간")
    st.metric(label="총 근무시간", value=f"{t_hrs:.1f} 시간")

else:
    st.subheader("🔐 사장님 관리자 모드")
    if st.text_input("관리자 비밀번호를 입력하세요", type="password", key="admin_pw") != "1234":
        st.info("관리자 비밀번호를 입력하세요. (기본: 1234)")
        st.stop()
    st.success("관리자 권한 확인됨")

    col_y, col_m = st.columns(2)
    target_year = col_y.selectbox("정산 연도", years_list, index=default_year_idx, key="adm_y")
    target_month = col_m.selectbox("정산 월", list(range(1, 13)), index=default_month_idx, key="adm_m")

start_date = date(target_year - 1, 12, 4) if target_month == 1 else date(target_year, target_month - 1, 4)
    end_date = date(target_year, target_month, 3)

    st.markdown("---")
    s_str = start_date.strftime("%m/%d")
    e_str = end_date.strftime("%m/%d")
    st.markdown(f"### 💸 전체 직원 급여 지출 예상 ({s_str} - {e_str})")
    
    tot_pay, summary = 0, []
