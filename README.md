# 게임부스터 (GameBooster)

모바일 게임용 휴대폰 최적화 앱 — 부스트 / 내 게임 / 상태 탭, 앱 안 업데이트.

## 새 버전 내는 법
1. 코드 수정
2. `VERSION` 파일의 버전 이름 올리기 (예: 1.2)
3. `RELEASE_NOTES.md`에 바뀐 내용 쓰기 (앱 업데이트 화면에 그대로 보임)
4. main에 올리면 GitHub Actions가 빌드 → 릴리스(v101, v102…) 생성 → 앱이 알림

## 필요한 시크릿 (Settings → Secrets and variables → Actions)
- `KEYSTORE_BASE64`: gb.keystore를 base64로 바꾼 값
- `KEYSTORE_PASSWORD`: 키 비밀번호
