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

# ==========================================
# [모드 A] 직원 근태 입력 화면
# ==========================================
if mode == "직원 근태 입력":
    st.subheader("📝 본인 근무시간 입력 (달력)")
    
    if not emp_list:
        st.stop()
        
    selected_emp = st.selectbox("본인의 이름을 선택하세요", emp_list, key="emp_select_main")
    emp_info = data[selected_emp]
    
    col_y, col_m = st.columns(2)
    with col_y:
        target_year = st.selectbox("정산 연도", [2026, 2027, 2028], index=0, key="emp_y")
    with col_m:
        target_month = st.selectbox("정산 월", list(range(1, 13)), index=datetime.now().month - 1, key="emp_m")

    if target_month == 1:
        start_date = date(target_year - 1, 12, 4)
    else:
        start_date = date(target_year, target_month - 1, 4)
    end_date = date(target_year, target_month, 3)

    st.write(f"**기록 기간:** {start_date.strftime('%Y.%m.%d')} - {end_date.strftime('%Y.%m.%d')}")

    if "records" not in emp_info:
        emp_info["records"] = {}

    all_dates = []
    curr = start_date
    while curr <= end_date:
        all_dates.append(curr)
        curr += timedelta(days=1)

    first_weekday = start_date.weekday()
    weeks = []
    current_week = [None] * first_weekday
    for d in all_dates:
        current_week.append(d)
        if len(current_week) == 7:
            weeks.append(current_week)
            current_week = []
    if current_week:
        while len(current_week) < 7:
            current_week.append(None)
        weeks.append(current_week)

    daily_inputs = {}

    with st.form("employee_record_form"):
        st.markdown("---")
        st.write("휴무이거나 결근한 날은 **0**으로 입력해 주세요.")
        
        hdr_cols = st.columns(7)
        hdr_cols[0].markdown("<div style='text-align: center; font-weight: bold;'>월</div>", unsafe_allow_html=True)
        hdr_cols[1].markdown("<div style='text-align: center; font-weight: bold;'>화</div>", unsafe_allow_html=True)
        hdr_cols[2].markdown("<div style='text-align: center; font-weight: bold;'>수</div>", unsafe_allow_html=True)
        hdr_cols[3].markdown("<div style='text-align: center; font-weight: bold;'>목</div>", unsafe_allow_html=True)
        hdr_cols[4].markdown("<div style='text-align: center; font-weight: bold;'>금</div>", unsafe_allow_html=True)
        hdr_cols[5].markdown("<div style='text-align: center; font-weight: bold; color: #3b82f6;'>토</div>", unsafe_allow_html=True)
        hdr_cols[6].markdown("<div style='text-align: center; font-weight: bold; color: #ef4444;'>일</div>", unsafe_allow_html=True)
        st.markdown("<hr style='margin:5px 0;'>", unsafe_allow_html=True)

        for w_idx, week in enumerate(weeks):
            row_cols = st.columns(7)
            for i, d in enumerate(week):
                with row_cols[i]:
                    if d is not None:
                        date_str = d.strftime("%Y-%m-%d")
                        
                        if i == 5:
                            lbl_color = "#3b82f6"
                        elif i == 6:
                            lbl_color = "#ef4444"
                        else:
                            lbl_color = "inherit"
                            
                        st.markdown(f"<div style='text-align: center; font-size: 14px; color: {lbl_color}; font-weight: bold;'>{d.month}/{d.day}</div>", unsafe_allow_html=True)
                        
                        default_val = emp_info["records"].get(date_str, "")
                        if default_val == "" and d.weekday() in emp_info["work_days"]:
                            default_val = emp_info["daily_hours"]
                        elif default_val == "":
                            default_val = 0.0

                        val = st.number_input(f"일자_{date_str}", value=float(default_val), step=0.5, format="%.1f", label_visibility="collapsed", key=f"emp_d_{w_idx}_{i}_{date_str}")
                        daily_inputs[date_str] = val
                    else:
                        st.write("")
            st.markdown("<div style='margin-bottom: 10px;'></div>", unsafe_allow_html=True)
            
        submitted_emp = st.form_submit_button("💾 내 근무 기록 저장하기", use_container_width=True)
        
        if submitted_emp:
            emp_info["records"].update(daily_inputs)
            save_data(data)
            st.success(f"[{selected_emp}] 님의 근무 기록이 안전하게 저장되었습니다!")

    # 예상 급여 계산 및 표시
    temp_records = emp_info.get("records", {}).copy()
    temp_records.update(daily_inputs)
    
    hourly_wage = emp_info.get('hourly_wage', 10834)
    apply_juhyu = emp_info.get('apply_juhyu', True)
    
    t_hours = 0
    t_juhyu_hours = 0
    daily_hrs = emp_info.get('daily_hours', 5.0)
    w_days_count = len(emp_info.get('work_days', []))
    weekly_work_hrs = daily_hrs * w_days_count
    juhyu_hrs_per_week = (weekly_work_hrs / 40
