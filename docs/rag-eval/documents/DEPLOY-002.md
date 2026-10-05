---
id: DEPLOY-002
title: 환경변수 누락으로 인한 기동 실패
---
# 환경변수 누락으로 인한 기동 실패

배포 후 파드가 기동에 실패했다. Cause: a required environment variable (PAYMENT_API_KEY) was missing in the new environment's config.
조치: 기동 시 필수 설정을 검사해 누락된 키 이름을 명확히 출력하도록 바꾸고, 배포 전 설정 diff 체크를 파이프라인에 추가했다.
