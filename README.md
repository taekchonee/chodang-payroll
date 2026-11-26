# 입구 LED 간판 자동 전환

입구의 CoolLEDX LED 패널(CoolLED1248 앱으로 쓰는 패널) 문구를 Home Assistant가 시간에 맞춰 자동으로 바꿉니다.

```
HA (영업 시간표, 조기마감 버튼)
   │  매장 네트워크 (HTTP)
   ▼
포스기의 "LED 간판" 앱   ← 패널 바로 옆에 있어서 블루투스가 닿음
   │  블루투스
   ▼
입구 LED 패널 (16×96)
```

| 폴더 | 내용 |
|---|---|
| `android/` | 포스기에 설치하는 안드로이드 앱 |
| `homeassistant/led_sign.yaml` | HA 설정 (영업 시간표, 문구, 자동화) |
| `.github/workflows/build-apk.yml` | GitHub가 앱(APK)을 자동으로 빌드 |

## 1. 포스기에 앱 설치

1. 포스기 브라우저에서 아래 주소를 열어 `led-sign.apk`를 받습니다.
   https://github.com/taekchonee/chodang-payroll/releases/download/led-sign-latest/led-sign.apk
2. 받은 파일을 눌러 설치합니다. "출처를 알 수 없는 앱" 허용을 물으면 허용합니다.
3. **LED 간판** 앱을 열고 **블루투스 권한을 허용**합니다.
4. **절전 예외 설정**을 눌러 허용합니다. (화면이 꺼져도 계속 동작하도록)
5. **패널 찾기**를 눌러 패널 이름(CoolLEDX-…)과 크기(96×16)가 나오는지 확인합니다.
6. **빠른 문구**의 `영업중`을 눌러 간판이 바뀌는지 확인합니다.

> 패널에는 한 번에 하나의 기기만 연결됩니다. 앱이 문구를 보낼 때 CoolLED1248 앱이 패널에 연결돼 있으면 실패할 수 있으니, CoolLED1248 앱은 닫아 두세요.
> 앱은 문구를 보낸 직후 연결을 끊습니다.

앱 화면의 **HA 주소**와 **접속 키**를 적어 둡니다.

## 2. HA 설정

1. `homeassistant/led_sign.yaml`을 HA 설정 폴더의 `packages/led_sign.yaml`로 복사합니다.
2. `configuration.yaml`에 아래가 없으면 추가합니다.
   ```yaml
   homeassistant:
     packages: !include_dir_named packages
   ```
3. `secrets.yaml`에 앱 화면의 값을 넣습니다.
   ```yaml
   led_sign_show_url: "http://포스기IP:8765/show"
   led_sign_key: "접속키6자리"
   ```
4. HA를 다시 시작합니다.

**포스기 IP 고정**: 공유기 설정에서 포스기에 고정 IP(DHCP 예약)를 걸어 두세요. IP가 바뀌면 HA가 포스기를 못 찾습니다.

### HA에 생기는 것
- **간판 상태** (`input_select.led_sign_status`): 바꾸면 간판 문구가 바로 바뀝니다.
- **오픈 / 브레이크 시작 / 브레이크 끝 / 마감 시간**: HA 화면에서 바로 수정합니다.
- **브레이크타임 사용**, **오늘 임시 휴무** 스위치
- **재료소진 조기마감** 스크립트: 대시보드에 버튼으로 올려 두면 휴대폰에서 한 번에 바꿀 수 있습니다. 마감 시간이 되면 자동으로 `영업종료`로 바뀝니다.
- 문구 5개 (`문구 - 영업중` 등): 간판에 나오는 글자를 HA 화면에서 바꿀 수 있습니다.
- 매주 **월요일**은 하루 종일 `휴무` 문구가 나옵니다.
- 전송에 실패하면 HA 알림이 뜹니다.

## 앱 직접 호출 (참고)

```
GET  http://포스기IP:8765/status
POST http://포스기IP:8765/show   {"key": "접속키", "text": "영업중", "color": "green", "mode": "auto"}
GET  http://포스기IP:8765/off?key=접속키
GET  http://포스기IP:8765/on?key=접속키
```

- `color`: red, green, blue, yellow, cyan, purple, white (패널이 낼 수 있는 7색)
- `mode`: `auto`(한 화면에 들어가면 고정, 길면 흐름), `static`, `left`, `right`

## 만든 방식

패널 통신 방식은 공개 드라이버 [coolledx](https://github.com/UpDryTwist/coolledx-driver) (MIT)를 코틀린으로 옮겼습니다.
APK는 고정된 공개 디버그 키로 서명합니다. 업데이트 설치가 가능하도록 하기 위한 것이며, 이 저장소의 릴리스에서만 받아 설치하세요.
