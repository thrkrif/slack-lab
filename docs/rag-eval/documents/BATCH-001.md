---
id: BATCH-001
title: 야간 배치 지연과 CPU 급증
---
# 야간 배치 지연과 CPU 급증

야간 정산 배치가 평소 20분에서 2시간으로 늘어났고 read replica CPU가 95%까지 올랐다.
The batch lost an index after a schema migration and fell back to full table scans.
조치: 인덱스를 복구하고 배치 동시성을 제한했으며, 마이그레이션 후 실행 계획 점검을 체크리스트에 넣었다.
