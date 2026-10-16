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

    tmp_recs = emp_info.get("records", {}).copy()
    tmp_recs.update(daily_inputs)
    h_wage = emp_info.get('hourly_wage', 10834)
    app_j = emp_info.get('apply_juhyu', True)
    
    t_hrs, t_j_hrs = 0, 0
    d_hrs = emp_info.get('daily_hours', 5.0)
    w_cnt = len(emp_info.get('work_days', []))
    j_per_w = (d_hrs * w_cnt / 40) * 8 if (app_j and w_cnt > 0) else 0

    curr = start_date
    while curr <= end_date:
        d_str = curr.strftime("%Y-%m-%d")
        t_hrs += tmp_recs.get(d_str, 0)
        if app_j and curr.weekday() == emp_info["holiday"]:
            w_start = curr - timedelta(days=6)
            perf = all(tmp_recs.get((w_start + timedelta(days=i)).strftime("%Y-%m-%d"), 0) != 0 for i in range(7) if start_date <= w_start + timedelta(days=i) <= end_date and (w_start + timedelta(days=i)).weekday() in emp_info["work_days"])
            if perf and w_cnt > 0: t_j_hrs += j_per_w
        curr += timedelta(days=1)

    est_tot = (t_hrs + t_j_hrs) * h_wage
    st.markdown("---")
    st.markdown("### ✨ 이번 달 나의 예상 급여")
    e1, e2, e3 = st.columns(3)
    e1.metric("총 근무시간", f"{t_hrs:.1f} 시간")
    e2.metric("주휴수당", f"{t_j_hrs * h_wage:,.0f} 원")
    e3.metric("예상 지급액", f"{est_tot:,.0f} 원")

else:
    st.subheader("🔐 사장님 관리자 모드")
    if st.text_input("관리자 비밀번호를 입력하세요", type="password", key="admin_pw") != "1234":
        st.info("관리자 비밀번호를 입력하세요. (기본: 1234)")
        st.stop()
    st.success("관리자 권한 확인됨")

    col_y, col_m = st.columns(2)
    target_year = col_y.selectbox("정산 연도", [2026, 2027, 2028], index=0, key="adm_y")
    target_month = col_m.selectbox("정산 월", list(range(1, 13)), index=datetime.now().month - 1, key="adm_m")

    start_date = date(target_year - 1, 12, 4) if target_month == 1 else date(target_year, target_month - 1, 4)
    end_date = date(target_year, target_month, 3)

    st.markdown("---")
    st.markdown(f"### 💸 전체 직원 급여 지출 예상 ({start_date.strftime('%m/%d')} - {end_date.strftime('%m/%d')})")
    
    tot_pay, summary = 0, []
    for e_name, e_dat in data.items():
        recs = e_dat.get("records", {})
        hw = e_dat.get('hourly_wage', 10834)
        aj = e_dat.get('apply_juhyu', True)
        dh = e_dat.get('daily_hours', 5.0)
        wd = e_dat.get('work_days', [])
        hol = e_dat.get('holiday', 0)
        
        s_hrs, s_j_hrs, wc = 0, 0, len(wd)
        jpw = (dh * wc / 40) * 8 if (aj and wc > 0) else 0

        c_date = start_date
        while c_date <= end_date:
            ds = c_date.strftime("%Y-%m-%d")
            s_hrs += recs.get(ds, 0)
            if aj and c_date.weekday() == hol:
                ws = c_date - timedelta(days=6)
                perf = all(recs.get((ws + timedelta(days=i)).strftime("%Y-%m-%d"), 0) != 0 for i in range(7) if start_date <= ws + timedelta(days=i) <= end_date and (ws + timedelta(days=i)).weekday() in wd)
                if perf and wc > 0: s_j_hrs += jpw
            c_date += timedelta(days=1)

        epay = (s_hrs + s_j_hrs) * hw
        tot_pay += epay
        summary.append({"직원명": e_name, "총근무시간": f"{s_hrs:.1f} 시간", "주휴시간": f"{s_j_hrs:.1f} 시간", "예상급여": f"{epay:,.0f} 원"})

    st.metric(label="🚨 이번 달 총 예상 인건비 지출 합계", value=f"{tot_pay:,.0f} 원")
    if summary: st.table(pd.DataFrame(summary))

    st.markdown("---")
    st.subheader("🔍 직원별 상세 정산 및 정보 관리")
    selected_emp = st.selectbox("상세 조회할 직원 선택", emp_list, key="admin_emp_target")
    emp_info = data[selected_emp]
    hw, aj = emp_info.get('hourly_wage', 10834), emp_info.get('apply_juhyu', True)

    recs = emp_info.get("records", {})
    tot_h, tot_jh = 0, 0
    dh = emp_info.get('daily_hours', 5.0)
    wd_cnt = len(emp_info.get('work_days', []))
    jpw_emp = (dh * wd_cnt / 40) * 8 if (aj and wd_cnt > 0) else 0

    curr = start_date
    while curr <= end_date:
        ds = curr.strftime("%Y-%m-%d")
        tot_h += recs.get(ds, 0)
        if aj and curr.weekday() == emp_info["holiday"]:
            ws = curr - timedelta(days=6)
            perf = all(recs.get((ws + timedelta(days=i)).strftime("%Y-%m-%d"), 0) != 0 for i in range(7) if start_date <= ws + timedelta(days=i) <= end_date and (ws + timedelta(days=i)).weekday() in emp_info["work_days"])
            if perf and wd_cnt > 0: tot_jh += jpw_emp
        curr += timedelta(days=1)

    r1, r2, r3 = st.columns(3)
    r1.metric("총 근무 시간", f"{tot_h:.1f} 시간", f"기본급: {tot_h * hw:,.0f}원")
    r2.metric("획득 주휴 시간", f"{tot_jh:.1f} 시간", f"주휴수당: {tot_jh * hw:,.0f}원")
    r3.metric("최종 지급액", f"{(tot_h + tot_jh) * hw:,.0f} 원", f"시급: {hw:,}원", delta_color="off")

    st.sidebar.header("👥 직원 관리 및 수정")
    act_type = st.sidebar.selectbox("작업 선택", ["기존 직원 정보 수정", "신규 직원 등록"], key="admin_action")
    curr_data = data[selected_emp] if act_type == "기존 직원 정보 수정" else {"hourly_wage": 10834, "daily_hours": 5.0, "work_days": [1,2,3,4], "holiday": 0, "apply_juhyu": True, "records": {}}

    with st.sidebar.form("emp_manage_form"):
        e_name_in = st.text_input("이름", value=selected_emp if act_type == "기존 직원 정보 수정" else "", key="new_name")
        hw_in = st.number_input("기본 시급 (원)", value=int(curr_data.get("hourly_wage", 10834)), step=10, key="edit_w")
        dh_in = st.number_input("1일 소정근로시간", value=float(curr_data.get("daily_hours", 5.0)), step=0.5, key="edit_h")
        
        st.write("소정근로일 선택")
        saved_wd = curr_data.get("work_days", [])
        wd_in = [i for i, d in enumerate(["월","화","수","목","금","토","일"]) if st.checkbox(d, value=i in saved_wd, key=f"w_chk_{i}")]
        
        hol_in = st.selectbox("주휴일 선택", options=range(7), format_func=lambda x: ["월","화","수","목","금","토","일"][x], index=int(curr_data.get("holiday", 0)), key="edit_hol")
        aj_in = st.checkbox("주휴수당 지급 대상 (주 15시간 이상)", value=bool(curr_data.get("apply_juhyu", True)), key="edit_aj")
        
        if st.form_submit_button("저장 및 반영하기"):
            t_name = e_name_in.strip()
            if not t_name:
                st.error("이름을 입력해주세요.")
            else:
                existing_recs = data.get(t_name, {}).get("records", {})
                data[t_name] = {"hourly_wage": hw_in, "daily_hours": dh_in, "work_days": wd_in, "holiday": hol_in, "apply_juhyu": aj_in, "records": existing_recs}
                save_data(data)
                st.success(f"[{t_name}] 정보가 저장되었습니다!")
                st.rerun()
