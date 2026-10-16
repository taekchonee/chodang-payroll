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

# 5번 요청: 메뉴 순서 변경 (직원 입력 위, 관리자 수정 아래)
mode = st.sidebar.radio("접속 모드", ["직원 근태 입력", "사장님 관리자 모드 (정산)"])

emp_list = list(data.keys())

if not emp_list and mode != "사장님 관리자 모드 (정산)":
    st.warning("등록된 직원이 없습니다. '사장님 관리자 모드'에서 먼저 직원을 등록해 주세요.")
    st.stop()

# ==========================================
# [모드 A] 직원 근태 입력 화면 (달력 및 예상 급여 추가)
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
        hdr_cols[5].markdown("<div style='text-align: center; font-weight: bold; color: #3b82f6;'>토</div>", unsafe_allow_html=True)
        hdr_cols[6].markdown("<div style='text-align: center; font-weight: bold; color: #ef4444;'>일</div>", unsafe_allow_html=True)
        st.markdown("<hr style='margin:5px 0;'>", unsafe_allow_html=True)

        for week in weeks:
            row_cols = st.columns(7)
            for i, d in enumerate(week):
                with row_cols[i]:
                    if d is not None:
                        date_str = d.strftime("%Y-%m-%d")
                        
                        # 1번 요청: 다크모드/라이트모드 모두 잘 보이도록 세련된 색상 지정 (토: 파랑, 일: 빨강, 평일: 강조톤)
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

                        # 2번 요청: 소수점 1자리 표기 (step=0.5)
                        val = st.number_input(f"일자_{date_str}", value=float(default_val), step=0.5, format="%.1f", label_visibility="collapsed", key=f"emp_d_{date_str}")
                        daily_inputs[date_str] = val
                    else:
                        st.write("")
            st.markdown("<div style='margin-bottom: 10px;'></div>", unsafe_allow_html=True)
            
        submitted_emp = st.form_submit_button("💾 내 근무 기록 저장하기", use_container_width=True)
        
        if submitted_emp:
            emp_info["records"].update(daily_inputs)
            save_data(data)
            st.success(f"[{selected_emp}] 님의 근무 기록이 안전하게 저장되었습니다!")

    # 3번 요청: 직원 페이지 하단 '예상 급여' 실시간 표시
    temp_records = emp_info.get("records", {})
    temp_records.update(daily_inputs) # 입력 중인 실시간 값 반영
    
    hourly_wage = emp_info.get('hourly_wage', 10834)
    apply_juhyu = emp_info.get('apply_juhyu', True)
    
    t_hours = 0
    t_juhyu_hours = 0
    daily_hrs = emp_info.get('daily_hours', 5.0)
    w_days_count = len(emp_info.get('work_days', []))
    weekly_work_hrs = daily_hrs * w_days_count
    juhyu_hrs_per_week = (weekly_work_hrs / 40) * 8 if (apply_juhyu and w_days_count > 0) else 0

    curr = start_date
    while curr <= end_date:
        date_str = curr.strftime("%Y-%m-%d")
        hours = temp_records.get(date_str, 0)
        t_hours += hours
        
        if apply_juhyu and curr.weekday() == emp_info["holiday"]:
            week_start = curr - timedelta(days=6)
            is_perfect = True
            for i in range(7):
                check_date = week_start + timedelta(days=i)
                if start_date <= check_date <= end_date:
                    if check_date.weekday() in emp_info["work_days"]:
                        if temp_records.get(check_date.strftime("%Y-%m-%d"), 0) == 0:
                            is_perfect = False
            if is_perfect and w_days_count > 0:
                t_juhyu_hours += juhyu_hrs_per_week
        curr += timedelta(days=1)

    est_base = t_hours * hourly_wage
    est_juhyu = t_juhyu_hours * hourly_wage
    est_total = est_base + est_juhyu

    st.markdown("---")
    st.markdown("### ✨ 이번 달 나의 예상 급여")
    e_col1, e_col2, e_col3 = st.columns(3)
    e_col1.metric("총 근무시간", f"{t_hours:.1f} 시간")
    e_col2.metric("주휴수당", f"{est_juhyu:,.0f} 원")
    e_col3.metric("예상 지급액", f"{est_total:,.0f} 원")

# ==========================================
# [모드 B] 사장님 관리자 모드 (전체 지출 예상 대시보드 추가)
# ==========================================
else:
    st.subheader("🔐 사장님 관리자 모드")
    
    password = st.text_input("관리자 비밀번호를 입력하세요", type="password")
    if password != "1234":
        st.info("관리자 모드에 진입하려면 올바른 비밀번호를 입력하세요. (기본 비밀번호: 1234)")
        st.stop()
        
    st.success("관리자 권한이 확인되었습니다.")

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

    # 4번 요청: 전체 직원 예상 급여 합계 및 개별 요약 대시보드 계산
    st.markdown("---")
    st.markdown(f"### 💸 전체 직원 급여 지출 예상 ({start_date.strftime('%m/%d')} - {end_date.strftime('%m/%d')})")
    
    total_store_pay = 0
    summary_data = []

    for emp_name, emp_dat in data.items():
        recs = emp_dat.get("records", {})
        h_wage = emp_dat.get('hourly_wage', 10834)
        app_juhyu = emp_dat.get('apply_juhyu', True)
        d_hrs = emp_dat.get('daily_hours', 5.0)
        w_days = emp_dat.get('work_days', [])
        hol = emp_dat.get('holiday', 0)
        
        sum_hrs = 0
        sum_j_hrs = 0
        w_cnt = len(w_days)
        j_per_w = (d_hrs * w_cnt / 40) * 8 if (app_juhyu and w_cnt > 0) else 0

        c_date = start_date
        while c_date <= end_date:
            d_str = c_date.strftime("%Y-%m-%d")
            hrs = recs.get(d_str, 0)
            sum_hrs += hrs
            
            if app_juhyu and c_date.weekday() == hol:
                w_start = c_date - timedelta(days=6)
                perf = True
                for i in range(7):
                    chk_d = w_start + timedelta(days=i)
                    if start_date <= chk_d <= end_date:
                        if chk_d.weekday() in w_days:
                            if recs.get(chk_d.strftime("%Y-%m-%d"), 0) == 0:
                                perf = False
                if perf and w_cnt > 0:
                    sum_j_hrs += j_per_w
            c_date += timedelta(days=1)

        b_pay = sum_hrs * h_wage
        j_pay = sum_j_hrs * h_wage
        e_pay = b_pay + j_pay
        total_store_pay += e_pay
        
        summary_data.append({
            "직원명": emp_name,
            "총근무시간": f"{sum_hrs:.1f} 시간",
            "주휴시간": f"{sum_j_hrs:.1f} 시간",
            "예상급여": f"{e_pay:,.0f} 원"
        })

    # 전체 지출 합계 메트릭 표시
    st.metric(label="🚨 이번 달 총 예상 인건비 지출 합계", value=f"{total_store_pay:,.0f} 원")
    
    if summary_data:
        st.table(pd.DataFrame(summary_data))

    st.markdown("---")
    st.subheader("🔍 직원별 상세 정산 및 정보 관리")
    
    selected_emp = st.selectbox("상세 조회할 직원 선택", emp_list, key="admin_emp_target")
    emp_info = data[selected_emp]
    hourly_wage = emp_info.get('hourly_wage', 10834)
    apply_juhyu = emp_info.get('apply_juhyu', True)

    # 상세 리포트 계산
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
                        if records.get(check_date.strftime("%Y-%m-%d"), 0) == 0:
                            is_perfect = False
            if is_perfect and w_days_count > 0:
                total_juhyu_hours += juhyu_hrs_per_week
        curr += timedelta(days=1)

    base_pay = total_hours * hourly_wage
    juhyu_pay = total_juhyu_hours * hourly_wage
    final_pay = base_pay + juhyu_pay

    r_col1, r_col2, r_col3 = st.columns(3)
    r_col1.metric("총 근무 시간", f"{total_hours:,.1f} 시간", f"기본급: {base_pay:,.0f}원")
    r_col2.metric("획득 주휴 시간", f"{total_juhyu_hours:,.1f} 시간", f"주휴수당: {juhyu_pay:,.0f}원")
    r_col3.metric("최종 지급액", f"{final_pay:,.0f} 원", f"시급: {hourly_wage:,}원", delta_color="off")

    # 사이드바: 직원 관리 및 수정 (5번 요청에 따라 아래로 배치)
    st.sidebar.header("👥 직원 관리 및 수정")
    action_type = st.sidebar.selectbox("작업 선택", ["기존 직원 정보 수정", "신규 직원 등록"])
    
    if action_type == "기존 직원 정보 수정":
        curr_data = data[selected_emp]
    else:
        curr_data = {
            "hourly_wage": 10834,
            "daily_hours": 5.0,
            "work_days": [1, 2, 3, 4],
            "holiday": 0,
            "apply_juhyu": True,
            "records": {}
        }

    with st.sidebar.form("emp_manage_form"):
        if action_type == "신
