import json
import os
from datetime import datetime
import streamlit as st

DATA_FILE = "payroll_data.json"


def load_data():
    if os.path.exists(DATA_FILE):
        try:
            with open(DATA_FILE, "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception:
            return {}
    return {}


def save_data(data):
    with open(DATA_FILE, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=4)


def main():
    st.set_page_config(
        page_title="초당예반 급여 및 근태 관리", page_icon="🌿", layout="centered"
    )

    data = load_data()

    st.title("🌿 초당예반 급여 및 근태 관리 시스템")

    mode = st.sidebar.radio("모드 선택", ["근태 및 급여 기록", "관리자 설정"])

    emp_list = list(data.keys())

    if mode == "근태 및 급여 기록":
        st.subheader("📋 직원 근태 및 급여 조회")

        if not emp_list:
            st.warning("등록된 직원이 없습니다. '관리자 설정'에서 먼저 직원을 등록해 주세요.")
            return

        selected_emp = st.selectbox("직원 선택", emp_list)

        if selected_emp in data:
            emp_info = data[selected_emp]
        else:
            emp_info = {}
            st.warning("선택한 직원의 데이터가 존재하지 않습니다.")
            return

        st.write(f"**이름:** {selected_emp}")
        st.write(
            f"**고용 형태 / 시급:** {emp_info.get('type', '시급제')} /"
            f" {emp_info.get('wage', 0):,}원"
        )

        st.markdown("---")
        st.markdown("### 근무 기록 입력")
        work_date = st.date_input("근무일", datetime.now())
        work_hours = st.number_input(
            "근무 시간 (시간)", min_value=0.0, max_value=24.0, step=0.5
        )

        if st.button("근무 기록 저장"):
            if "shifts" not in emp_info:
                emp_info["shifts"] = []
            emp_info["shifts"].append({
                "date": str(work_date),
                "hours": work_hours,
            })
            data[selected_emp] = emp_info
            save_data(data)
            st.success("근무 기록이 성공적으로 저장되었습니다!")

        st.markdown("---")
        st.markdown("### 💰 급여 산정 및 명세서")
        shifts = emp_info.get("shifts", [])
        total_hours = sum(s.get("hours", 0) for s in shifts)
        hourly_wage = emp_info.get("wage", 0)
        gross_pay = total_hours * hourly_wage

        st.write(f"총 누적 근무 시간: {total_hours} 시간")
        st.write(f"총 급여 (세전): {gross_pay:,.0f} 원")

        tax_type = emp_info.get("tax_type", "3.3%")
        if tax_type == "4대보험":
            tax_amount = gross_pay * 0.0913
        else:
            tax_amount = gross_pay * 0.033

        net_pay = gross_pay - tax_amount
        st.write(f"공제액 ({tax_type}): {tax_amount:,.0f} 원")
        st.write(f"실지급액: **{net_pay:,.0f} 원**")

    elif mode == "관리자 설정":
        st.subheader("⚙️ 관리자 설정 (직원 관리)")

        st.markdown("### 새 직원 등록")
        new_name = st.text_input("직원 이름")
        new_wage = st.number_input("시급 (원)", min_value=0, step=1000)
        new_tax_type = st.selectbox("공제 유형", ["3.3%", "4대보험"])

        if st.button("직원 추가 / 수정"):
            if new_name:
                if new_name not in data:
                    data[new_name] = {
                        "wage": new_wage,
                        "tax_type": new_tax_type,
                        "shifts": [],
                    }
                else:
                    data[new_name]["wage"] = new_wage
                    data[new_name]["tax_type"] = new_tax_type
                save_data(data)
                st.success(f"'{new_name}' 정보가 저장되었습니다.")
                st.rerun()
            else:
                st.error("직원 이름을 입력해주세요.")

        st.markdown("---")
        st.markdown("### 등록된 직원 목록")
        if data:
            for emp, info in data.items():
                st.write(
                    f"- **{emp}** (시급: {info.get('wage', 0):,}원, 유형:"
                    f" {info.get('tax_type', '3.3%')})"
                )
        else:
            st.info("등록된 직원이 없습니다.")


if __name__ == "__main__":
    main()
