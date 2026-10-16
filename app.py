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

# 1. 모드 선택 (직원용 vs 사장님 관리자용)
mode = st.sidebar.radio("접속 모드", ["직원 근태 입력", "사장님 관리자 모드 (정산)"])

emp_list = list(data.keys())

if not emp_list:
    st.warning("등록된 직원이 없습니다. '사장님 관리자 모드'에서 먼저 직원을 등록해 주세요.")
    if mode != "사장님 관리자 모드 (정산)":
        st.stop()

# ==========================================
# [모드 A] 직원 근태 입력 화면
# ==========================================
if mode == "직원 근태 입력":
    st.subheader("📝 본인 근무시간 입력")
    
    if not emp_list:
        st.stop()
        
    selected_emp = st.selectbox("본인의 이름을 선택하세요", emp_list)
    emp_info = data[selected_emp]
    
    # 정산 월 선택
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

    current_date = start_date
    daily_inputs = {}

    with st.form("employee_record_form"):
        st.markdown("---")
        st.write("휴무이거나 결근한 날은 **0**으로 입력해 주세요.")
        
        col_grid = st.columns(4)
        idx = 0
        temp_date = start_date
        
        while temp_date <= end_date:
            date_str = temp_date.strftime("%Y-%m-%d")
            d_label = f"{temp_date.month}/{temp_date.day} ({['월','화','수','목','금','토','일'][temp_date.weekday()]})"
            
            default_val = emp_info["records"].get(date_str, "")
            if default_val == "" and temp_date.weekday() in emp_info["work_days"]:
                default_val = emp_info["daily_hours"]
            elif default_val == "":
                default_val = 0.0

            with col_grid[idx % 4]:
                val = st.number_input(d_label, value=float(default_val), step=0.5, key=f"emp_d_{date_str}")
                daily_inputs[date_str] = val
                
            idx += 1
            temp_date += timedelta(days=1)
            
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
    
    # 관리자 비밀번호 보호 (예: 1234 - 필요시 변경 가능)
    password = st.text_input("관리자 비밀번호를 입력하세요", type="password")
    if password != "1234":  # 원하시는 비밀번호로 변경하세요
        st.info("관리자 모드에 진입하려면 올바른 비밀번호를 입력하세요. (기본 비밀번호: 1234)")
        st.stop()
        
    st.success("관리자 권한이 확인되었습니다.")
    
    st.sidebar.header("👥 직원 관리")
    selected_emp = st.sidebar.selectbox("직원 선택", emp_list if emp_list else ["등록된 직원 없음"], key="admin_emp")

    with st.sidebar.expander("➕ 새 직원 등록/수정"):
        with st.form("emp_form"):
            new_name = st.text_input("이름")
            monthly_salary = st.number_input("월 급여총액 (원)", value=1129770, step=10000)
            daily_hours = st.number_input("1일 소정근로시간", value=5.0, step=0.5)
            
            st.write("소정근로일 선택")
            days_label = ["월", "화", "수", "목", "금", "토", "일"]
            work_days = []
            default_work = [False, True, True, True, True, False, False]
            chk_cols = st.columns(7)
            for i, d in enumerate(days_label):
                with chk_cols[i]:
                    if st.checkbox(d, value=default_work[i], key=f"w_{i}"):
                        work_days.append(i)
                        
            holiday = st.selectbox("주휴일 선택", options=range(7), format_func=lambda x: days_label[x], index=0)
            
            submitted = st.form_submit_button("직원 정보 저장")
            if submitted and new_name:
                data[new_name] = {
                    "monthly_salary": monthly_salary,
                    "daily_hours": daily_hours,
                    "work_days": work_days,
                    "holiday": holiday,
                    "records": data.get(new_name, {}).get("records", {})
                }
                save_data(data)
                st.success(f"{new_name} 등록 완료!")
                st.rerun()

    if not selected_emp or selected_emp == "등록된 직원 없음":
        st.stop0 = True
        st.stop()

    emp_info = data[selected_emp]

    # 시급 자동 산출
    weekly_work_hours = emp_info['daily_hours'] * len(emp_info['work_days'])
    juhyu_hours_per_week = (weekly_work_hours / 40) * 8
    weekly_paid_hours = weekly_work_hours + juhyu_hours_per_week
    monthly_paid_hours = weekly_paid_hours * 4.345
    hourly_wage = round(emp_info['monthly_salary'] / monthly_paid_hours) if monthly_paid_hours > 0 else 0

    st.sidebar.divider()
    st.sidebar.metric(label="산정 시급", value=f"{hourly_wage:,} 원")

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

    curr = start_date
    while curr <= end_date:
        date_str = curr.strftime("%Y-%m-%d")
        hours = records.get(date_str, 0)
        total_hours += hours
        
        if curr.weekday() == emp_info["holiday"]:
            week_start = curr - timedelta(days=6)
            is_perfect = True
            
            for i in range(7):
                check_date = week_start + timedelta(days=i)
                if start_date <= check_date <= end_date:
                    if check_date.weekday() in emp_info["work_days"]:
                        chk_hours = records.get(check_date.strftime("%Y-%m-%d"), 0)
                        if chk_hours == 0:
                            is_perfect = False
                
            if is_perfect and len(emp_info["work_days"]) > 0:
                total_juhyu_hours += juhyu_hours_per_week
                
        curr += timedelta(days=1)

    base_pay = total_hours * hourly_wage
    juhyu_pay = total_juhyu_hours * hourly_wage
    final_pay = base_pay + juhyu_pay

    st.markdown("---")
    st.markdown(f"### 📊 [{selected_emp}] 님 최종 정산 리포트")
    r_col1, r_col2, r_col3 = st.columns(3)
    r_col1.metric("총 근무 시간", f"{total_hours:,.1f} 시간", f"기본급: {base_pay:,.0f}원")
    r_col2.metric("획득 주휴 시간", f"{total_juhyu_hours:,.1f} 시간", f"주휴수당: {juhyu_pay:,.0f}원")
    r_col3.metric("최종 지급액", f"{final_pay:,.0f} 원", f"시급: {hourly_wage:,}원", delta_color="off")