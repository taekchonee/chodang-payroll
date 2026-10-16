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
# [모드 A] 직원 근태 입력 화면 (7열 달력형식)
# ==========================================
if mode == "직원 근태 입력":
    st.subheader("📝 본인 근무시간 입력 (달력)")
    
    if not emp_list:
        st.stop()
        
    selected_emp = st.selectbox("본인의 이름을 선택하세요", emp_list)
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
        hdr_cols[5].markdown("<div style='text-align: center; font-weight: bold; color: blue;'>토</div>", unsafe_allow_html=True)
        hdr_cols[6].markdown("<div style='text-align: center; font-weight: bold; color: red;'>일</div>", unsafe_allow_html=True)
        st.markdown("<hr style='margin:5px 0;'>", unsafe_allow_html=True)

        for week in weeks:
            row_cols = st.columns(7)
            for i, d in enumerate(week):
                with row_cols[i]:
                    if d is not None:
                        date_str = d.strftime("%Y-%m-%d")
                        
                        if i == 5:
                            lbl_color = "blue"
                        elif i == 6:
                            lbl_color = "red"
                        else:
                            lbl_color = "black"
                            
                        st.markdown(f"<div style='text-align: center; font-size: 14px; color: {lbl_color}; font-weight: bold;'>{d.month}/{d.day}</div>", unsafe_allow_html=True)
                        
                        default_val = emp_info["records"].get(date_str, "")
                        if default_val == "" and d.weekday() in emp_info["work_days"]:
                            default_val = emp_info["daily_hours"]
                        elif default_val == "":
                            default_val = 0.0

                        val = st.number_input(f"일자_{date_str}", value=float(default_val), step=0.5, label_visibility="collapsed", key=f"emp_d_{date_str}")
                        daily_inputs[date_str] = val
                    else:
                        st.write("")
            st.markdown("<div style='margin-bottom: 10px;'></div>", unsafe_allow_html=True)
            
        submitted_emp = st.form_submit_button("💾 내 근무 기록 저장하기", use_container_width=True)
        
        if submitted_emp:
            emp_info["records"].update(daily_inputs)
            save_data(data)
            st.success(f"[{selected_emp}] 님의 근무 기록이 안전하게 저장되었습니다!")

# ==========================================
# [모드 B] 사장님 관리자 모드 (급여 정산 및 설정)
# ==========================================
else:
    st.subheader("🔐 사장님 관리자 모드")
    
    password = st.text_input("관리자 비밀번호를 입력하세요", type="password")
    if password != "1234":
        st.info("관리자 모드에 진입하려면 올바른 비밀번호를 입력하세요. (기본 비밀번호: 1234)")
        st.stop()
        
    st.success("관리자 권한이 확인되었습니다.")
    
    st.sidebar.header("👥 직원 관리 및 수정")
    
    # 신규 등록 vs 기존 수정 선택 옵션
    action_type = st.sidebar.selectbox("작업 선택", ["기존 직원 정보 수정", "신규 직원 등록"])
    
    if action_type == "기존 직원 정보 수정":
        if not emp_list:
            st.warning("등록된 직원이 없습니다. '신규 직원 등록'을 먼저 진행해 주세요.")
            st.stop()
        selected_emp = st.sidebar.selectbox("수정할 직원 선택", emp_list, key="edit_emp_select")
        curr_data = data[selected_emp]
    else:
        selected_emp = None
        curr_data = {
            "hourly_wage": 10834,
            "daily_hours": 5.0,
            "work_days": [1, 2, 3, 4], # 화~금 기본
            "holiday": 0, # 월요일 기본
            "apply_juhyu": True,
            "records": {}
        }

    with st.sidebar.form("emp_manage_form"):
        if action_type == "신규 직원 등록":
            st.write("### ➕ 신규 직원 등록")
            emp_name_input = st.text_input("이름")
        else:
            st.write(f"### ✏️ [{selected_emp}] 정보 수정")
            emp_name_input = selected_emp # 이름은 고정 (변경 필요시 삭제 후 재등록 권장)

        hourly_wage = st.number_input("기본 시급 (원)", value=int(curr_data.get("hourly_wage", 10834)), step=10)
        daily_hours = st.number_input("1일 소정근로시간", value=float(curr_data.get("daily_hours", 5.0)), step=0.5)
        
        st.write("소정근로일 선택")
        days_label = ["월", "화", "수", "목", "금", "토", "일"]
        saved_work_days = curr_data.get("work_days", [])
        
        work_days = []
        chk_cols = st.columns(7)
        for i, d in enumerate(days_label):
            with chk_cols[i]:
                is_checked = i in saved_work_days
                if st.checkbox(d, value=is_checked, key=f"w_edit_{i}"):
                    work_days.append(i)
                    
        holiday = st.selectbox("주휴일 선택", options=range(7), format_func=lambda x: days_label[x], index=int(curr_data.get("holiday", 0)))
        apply_juhyu = st.checkbox("주휴수당 지급 대상 (주 15시간 이상)", value=bool(curr_data.get("apply_juhyu", True)))
        
        submitted_mgmt = st.form_submit_button("저장 및 반영하기")
        
        if submitted_mgmt:
            target_name = emp_name_input.strip()
            if not target_name:
                st.error("이름을 입력해주세요.")
            else:
                # 기존 레코드 보존하면서 정보 업데이트
                existing_records = data.get(target_name, {}).get("records", {})
                data[target_name] = {
                    "hourly_wage": hourly_wage,
                    "daily_hours": daily_hours,
                    "work_days": work_days,
                    "holiday": holiday,
                    "apply_juhyu": apply_juhyu,
                    "records": existing_records
                }
                save_data(data)
                st.success(f"[{target_name}] 정보가 성공적으로 저장되었습니다!")
                st.rerun()

    if not emp_list:
        st.stop()

    # 메인 정산 화면용 직원 셀렉터
    st.sidebar.divider()
    admin_selected_emp = st.sidebar.selectbox("정산 조회할 직원 선택", emp_list, key="admin_emp_target")
    emp_info = data[admin_selected_emp]
    hourly_wage = emp_info.get('hourly_wage', 10834)
    apply_juhyu = emp_info.get('apply_juhyu', True)

    st.sidebar.metric(label="설정된 기본 시급", value=f"{hourly_wage:,} 원")
    st.sidebar.write(f"주휴수당 적용: {'포함 (주 15시간 이상)' if apply_juhyu else '제외 (주 15시간 미만)'}")

    col_y, col_m = st.columns(2)
    with col_y:
        target_year = st.selectbox("정산 연도", [2026, 2027, 2028], index=0, key="adm_y")
    with col_m:
        target_month = st.selectbox("정산 월", list(range(1, 13)), index=datetime.now().month - 1, key="adm_m")

    if target_month == 1:
        start_date = date(target_year - 1, 12, 4)
    else:
        start_date = date(target_year, target_month - 1, 4)
    end_date = date(target_year, target_month, 3)

    st.write(f"**급여 산정 기간:** {start_date.strftime('%Y.%m.%d')} - {end_date.strftime('%Y.%m.%d')}")

    records = emp_info.get("records", {})
    total_hours = 0
    total_juhyu_hours = 0

    daily_hrs = emp_info.get('daily_hours', 5.0)
    w_days_count = len(emp_info.get('work_days', []))
    weekly_work_hrs = daily_hrs * w_days_count
    juhyu_hrs_per_week = (weekly_work_hrs / 40) * 8 if (apply_juhyu and w_days_count > 0) else 0

    curr = start_date
    while curr <= end_date:
        date_str = curr.strftime("%Y-%m-%d")
        hours = records.get(date_str, 0)
        total_hours += hours
        
        if apply_juhyu and curr.weekday() == emp_info["holiday"]:
            week_start = curr - timedelta(days=6)
            is_perfect = True
            
            for i in range(7):
                check_date = week_start + timedelta(days=i)
                if start_date <= check_date <= end_date:
                    if check_date.weekday() in emp_info["work_days"]:
                        chk_hours = records.get(check_date.strftime("%Y-%m-%d"), 0)
                        if chk_hours == 0:
                            is_perfect = False
                
            if is_perfect and w_days_count > 0:
                total_juhyu_hours += juhyu_hrs_per_week
                
        curr += timedelta(days=1)

    base_pay = total_hours * hourly_wage
    juhyu_pay = total_juhyu_hours * hourly_wage
    final_pay = base_pay + juhyu_pay

    st.markdown("---")
    st.markdown(f"### 📊 [{admin_selected_emp}] 님 최종 정산 리포트")
    r_col1, r_col2, r_col3 = st.columns(3)
    r_col1.metric("총 근무 시간", f"{total_hours:,.1f} 시간", f"기본급: {base_pay:,.0f}원")
    r_col2.metric("획득 주휴 시간", f"{total_juhyu_hours:,.1f} 시간", f"주휴수당: {juhyu_pay:,.0f}원")
    r_col3.metric("최종 지급액", f"{final_pay:,.0f} 원", f"시급: {hourly_wage:,}원", delta_color="off")
