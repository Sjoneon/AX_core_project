# Vercel 자동 배포와 운영·복구

2026-10-13, 8단계 구현·실제 Git 배포·브라우저·RDS 백업 복구 검증을 완료했습니다.
이메일·예산 알림은 사용자 요청으로 생략합니다.
운영 컨테이너는 `backend`, `nginx` 두 개이며 추가 상시 컨테이너는 없습니다.

## Vercel Git 배포

기존 `homecare` 프로젝트에 `gwonu1/core_project`를 연결했습니다.
Production branch는 `main`, Root Directory는 `frontend`, Node는 `24.x`입니다.
설치·빌드·출력 설정은 [frontend/vercel.json](../frontend/vercel.json)에 기록합니다.
공개 운영 설정은 `VITE_API_BASE_URL=https://43.202.80.116`입니다.
일반 Git 연동에는 Vercel 토큰을 GitHub Secrets에 등록할 필요가 없습니다.

Production Deployment Checks 두 개를 등록했습니다.

| 검사 | GitHub job | 대기 상한 | 적용 |
|---|---|---|---|
| GitHub ci | `ci` | 30분 | 운영 도메인 승격 차단 |
| GitHub deploy | `deploy` | 60분 | 운영 도메인 승격 차단 |

Vercel은 Git push로 프론트엔드를 빌드하고, 동일 커밋의 GitHub CI와 백엔드 배포가
성공해야 기존 `homecare-omega-eight.vercel.app`에 새 빌드를 반영합니다.
일반 자동 도메인 할당은 켜져 있으며 필수 검사가 승격을 제어합니다.
`Force Promote`는 검사를 우회하므로 정상 배포 절차로 사용하지 않습니다.
검사 timeout이면 GitHub와 Vercel의 해당 커밋 결과를 확인하고 재배포합니다.
GitHub job 이름을 변경할 때 Vercel 검사도 함께 변경해야 합니다.

등록된 프로젝트 ID·검사 ID는 [공개 설정 기록](VERCEL_DEPLOYMENT.json)에 보관합니다.
CLI를 사용할 때는 `.vercel`로 연결한 **저장소 루트**에서 실행합니다.
Git Root Directory가 `frontend`이므로 과거처럼 `frontend/` 안에서 로컬 배포하지 않습니다.
CLI link가 내려받은 임시 `.env.local`은 값 출력 없이 제거했고 해당 파일과 `.vercel`은 Git에서 제외합니다.

Preview origin에는 운영 API CORS 권한을 추가하지 않습니다.
Preview 프론트엔드에서 운영 상담을 사용하려면 별도 검증 환경이 필요합니다.
현재 서비스는 운영 환경 하나로 시작합니다.

## 운영 대시보드

[CloudWatch homecare-prod 대시보드](https://ap-northeast-2.console.aws.amazon.com/cloudwatch/home?region=ap-northeast-2#dashboards/dashboard/homecare-prod)

Terraform의 [monitoring.tf](../terraform-aws-ec2/monitoring.tf)가 대시보드를 관리합니다.
EC2에서 [report-metrics.py](report-metrics.py)를 systemd 타이머로 분당 실행합니다.
호스트 메모리·디스크·검증된 HTTPS health·인증서 남은 시간·Certbot 타이머 상태를
`homecare/prod` namespace에 전송합니다. EC2 역할은 이 namespace에만 전송할 수 있습니다.
앱 설정·JWT·DB 비밀번호·사용자 요청·응답 본문은 읽거나 메트릭으로 보내지 않습니다.

EC2 기본 CPU·상태·크레딧은 5분 단위, RDS 저장공간·연결 수는 AWS 기본 메트릭을 사용합니다.
메모리는 `MemAvailable` 기준으로 계산해 회수 가능한 캐시를 고려합니다.
디스크 85% 이상, 인증서 남은 시간 48시간 미만이면 원인을 확인합니다.
160시간 IP 인증서에 대한 자동 갱신 타이머와 reload hook은 계속 유지합니다.

현재는 콘솔 관측만 구성했고 이메일 장애 알림·CloudWatch alarms·SNS·예산 알림은 생성하지 않았습니다.
분당 메트릭 수집은 CloudWatch 사용량에 따라 요금이 발생할 수 있습니다.
호스트가 꺼지거나 타이머·네트워크·IAM에 문제가 있으면 사용자 메트릭이 누락됩니다.
메트릭 누락은 정상으로 간주하지 않고 EC2 상태와 타이머를 확인합니다.
HTTPS health는 EC2에서 측정하므로 인터넷 전체 경로의 가용성 측정은 아닙니다.
GitHub 배포에서는 별도로 runner의 외부 HTTPS health를 확인합니다.

ECR은 태그 없는 이미지에만 7일 정리 정책을 적용하며 태그 있는 릴리스는 보존합니다.
S3 릴리스와 EC2의 `current`·`previous`는 자동 삭제하지 않습니다.
디스크 정리가 필요하면 두 링크의 Compose·이미지 digest·설정 스냅샷을 먼저 확인하고,
현재·직전 정상 이미지와 릴리스는 보존합니다. 운영 DB·TLS 키·설정 파일은 이미지 정리 대상이 아닙니다.

```bash
# 최초 설치 또는 스크립트 갱신: 두 파일이 있는 디렉터리에서 EC2 root로 실행
bash install-monitoring.sh i-0223afa15057a76b2
systemctl status homecare-metrics.timer homecare-metrics.service
journalctl -u homecare-metrics.service --since '10 minutes ago'
systemctl status snap.certbot.renew.timer
```

## 백업과 복구

운영 RDS는 비공개·암호화·삭제 보호·삭제 시 최종 스냅샷을 유지합니다.
현재 무료 플랜의 자동 백업 보존은 사용자 선택에 따라 1일입니다.
운영 서비스의 7일 목표는 계정 플랜 변경 후 별도로 적용합니다.
앱 배포 롤백은 RDS를 되돌리지 않습니다.

복구는 운영 DB를 덮어쓰지 않고 자동 백업의 시점으로 **새 RDS에 복원**합니다.
같은 VPC의 비공개 DB subnet group·앱 SG만 허용하는 DB SG·TLS parameter group을 사용합니다.
새 주소에서 계정·상담·메시지와 인증서 검증 연결을 확인한 뒤 실제 전환을 판단합니다.
전환이 필요한 실제 사고에서는 쓰기를 조정하고 Parameter Store 연결 주소를 바꾼 다음
새 앱 릴리스로 반영합니다. endpoint 변경·DB 사용자 비밀번호 갱신은 자동으로 수행하지 않습니다.

이번 실습의 임시 복원 대상은 `homecare-prod-restore-check-20261013`입니다.
사용자가 추가 비용을 승인했고, 실제 복구 데이터를 검증한 뒤 이 임시 대상을 삭제했습니다.
실습용 복사본은 최종 스냅샷 없이 삭제하고 운영 DB·기존 자동 백업은 유지합니다.
[verify-backup-data.py](verify-backup-data.py)는 UUID 합성 사용자·상담만 생성·확인·정리합니다.
복원 명령은 로컬 AWS CLI 자격증명으로 실행하고, 데이터 검증은 EC2의 기존 앱 계정으로 수행합니다.
DB 관리자 비밀번호를 조회하거나 EC2에 관리자 비밀 조회 권한을 추가하지 않았습니다.
검증 기록은 EC2의 root 전용 폴더에 두고 앱 비밀값을 로컬이나 Git으로 옮기지 않습니다.
실제 MySQL PITR 요청에서 `ManageMasterUserPassword` 옵션이 거부돼 해당 옵션을 제외했습니다.
복원 데이터는 기존 앱 계정으로 확인했으며 운영 DB의 암호·주소·스키마는 바꾸지 않았습니다.

## 검증 상태

- Terraform mock 테스트 4개와 운영 스크립트 테스트 23개 통과.
- 대시보드 생성·제한된 메트릭 권한 적용·systemd 타이머 설치·5개 실제 메트릭 수신 확인.
- Vercel Git 연결·프로젝트 빌드 설정·운영 승격 필수 검사 2개·운영 도메인 등록 확인.
- [실제 Git 배포](https://github.com/gwonu1/core_project/actions/runs/38330964092): Vercel 빌드가 STAGED로 대기하고 같은 커밋 `ci`·`deploy` 성공 후 PROMOTED로 전환됐습니다. 기존 운영 도메인의 새 배포 연결과 공개 번들의 HTTPS API 주소를 확인했습니다.
- 실제 브라우저 회원가입·로그인·첫 AI 상담·후속 상담·검색 근거·상담 내역 재조회·확인 창의 삭제·빈 내역·로그아웃을 검증했습니다. 사용자 승인 후 OpenAI를 호출했고 합성 상담·계정을 정리했습니다.
- 자동 백업이 합성 데이터의 커밋 시점을 포함할 때까지 기다린 뒤 별도 RDS에 시점 복원했습니다. 복원 사용자·상담 소유권·메시지 2개·한글/이모지·TLS를 확인했습니다. 임시 RDS 삭제와 원본 합성 데이터 정리, 운영 DB 주소 유지도 확인했습니다.
- 모니터링 적용 후 실제 Terraform plan은 변경 없음입니다.
- EC2 재부팅 자동 복구·인증서 갱신 리허설·실제 앱 실패 복원은 각각 5·6·7단계에서 검증했습니다.

SSM command ID와 첫 Git 배포·RDS 복원 검증은 [완료 기록](STAGE8_OPERATIONS.json)에 보관합니다.
기록은 검증 시점 기준이며 이후 현재 릴리스는 Actions·Vercel 배포 결과와 EC2 `current` 링크에서 확인합니다.

참고: [Vercel Deployment Checks](https://vercel.com/docs/deployment-checks),
[Vercel Git](https://vercel.com/docs/git),
[RDS 시점 복구](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/USER_PIT.html),
[CloudWatch 사용자 메트릭](https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/publishingMetrics.html).
