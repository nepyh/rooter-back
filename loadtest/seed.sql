-- 부하 테스트용 시드 데이터. 로컬 DB(docker-compose.dev.yml 의 db)에만 넣는다 — 운영 DB 에 넣지 않는다.
-- 사용: docker exec -i rooter-back-dev-db psql -U $DB_USER -d $DB_NAME -v local=1 -v users=300 < loadtest/seed.sql
-- 마스터 데이터(과목·출판사·교과서)에 테스트용 행을 넣으므로 운영 DB 에서 실행하면 데이터가 오염된다.
-- 그래서 -v local=1 을 직접 붙여야 하고, 컨테이너 안 소켓·localhost 접속이 아니면 멈춘다.
-- 계정: loadtest1@rooter.test ~ loadtest{users}@rooter.test / 비밀번호 loadtest1234!
-- 학생마다 플랜보드 1개(오늘 기준 -14일 ~ +14일), 과목 4개, 하루 할일 4개, 캘린더 일정 3개.
\set ON_ERROR_STOP on
\if :{?local}
\else
  \echo '중단: 로컬 DB 에만 넣는 시드입니다. 로컬이 맞으면 -v local=1 을 붙여 다시 실행하세요.'
  \quit
\endif
select (inet_server_addr() is null or host(inet_server_addr()) in ('127.0.0.1', '::1')) as is_local_conn \gset
\if :is_local_conn
\else
  \echo '중단: 소켓·localhost 접속이 아닙니다 (원격 DB 로 보임). docker exec 로 컨테이너 안에서 실행하세요.'
  \quit
\endif
\if :{?users}
\else
  \set users 300
\endif

begin;

create extension if not exists pgcrypto;

-- 이전 시드 지우기 (사용자를 지우면 플랜보드·할일·일정은 cascade 로 함께 지워진다)
delete from users where email like 'loadtest%@rooter.test';

-- 마스터 데이터 (없을 때만)
insert into subjects (name) values ('국어'), ('영어'), ('수학'), ('과학')
on conflict (name) do nothing;
insert into publishers (name) values ('부하테스트출판') on conflict (name) do nothing;

insert into textbooks (subject_id, publisher_id, title, ai_status)
select s.id, p.id, s.name || ' 부하테스트 교과서', 'completed'
from subjects s cross join publishers p
where s.name in ('국어', '영어', '수학', '과학') and p.name = '부하테스트출판'
  and not exists (select 1 from textbooks t where t.subject_id = s.id and t.title = s.name || ' 부하테스트 교과서');

insert into chapters (textbook_id, chapter_name, chapter_order)
select t.id, n || '단원', n
from textbooks t cross join generate_series(1, 5) n
where t.title like '% 부하테스트 교과서'
  and not exists (select 1 from chapters c where c.textbook_id = t.id);

-- 학생 계정 (bcrypt 해시는 한 번만 만들어 모두 같은 비밀번호)
with pw as (select crypt('loadtest1234!', gen_salt('bf', 10)) as hash)
insert into users (email, username, password, sleep_time, wake_time, commute_minutes)
select 'loadtest' || n || '@rooter.test', 'lt' || n, pw.hash, '23:00', '07:00', 20
from generate_series(1, :users) n cross join pw;

insert into plan_boards (user_id, title, start_date, end_date, exam_date)
select u.id, '기말고사 대비', current_date - 14, current_date + 14, current_date + 14
from users u where u.email like 'loadtest%@rooter.test';

insert into plan_subjects (plan_board_id, textbook_id, start_chapter_id, end_chapter_id)
select b.id, t.id,
       (select min(c.id) from chapters c where c.textbook_id = t.id),
       (select max(c.id) from chapters c where c.textbook_id = t.id)
from plan_boards b
join users u on u.id = b.user_id and u.email like 'loadtest%@rooter.test'
cross join textbooks t where t.title like '% 부하테스트 교과서';

insert into daily_plans (plan_board_id, plan_date)
select b.id, d::date
from plan_boards b
join users u on u.id = b.user_id and u.email like 'loadtest%@rooter.test'
cross join generate_series(current_date - 14, current_date + 14, interval '1 day') d;

-- 하루 4개: 23:00 부터 10분 공부 + 5분 쉬기. 지난 날은 대부분 완료.
-- 스케줄러(task_quiz_trigger)는 "오늘 할일 중 끝난 시각이 지난 것"마다 실제 LLM 으로 퀴즈를 만든다.
-- 오늘 할일이 측정 중에 끝나면 부하에 섞이고 비용도 나가므로, 끝나는 시각을 밤 23시대로 둔다.
-- → 측정은 시드를 넣은 날 23:00 전에 끝낸다. 날이 바뀌면 시드를 다시 넣는다.
insert into plan_tasks (daily_plan_id, task_name, start_time, end_time, estimated_minutes, subject_id, status, origin)
select dp.id,
       s.name || ' ' || (k + 1) || '단원 복습',
       make_time(23, k * 15, 0), make_time(23, k * 15 + 10, 0), 10,
       s.id,
       case when dp.plan_date < current_date and (dp.id + k) % 5 <> 0 then 'DONE'
            when dp.plan_date < current_date then 'FAILED'
            else 'PENDING' end,
       'AI'
from daily_plans dp
join plan_boards b on b.id = dp.plan_board_id
join users u on u.id = b.user_id and u.email like 'loadtest%@rooter.test'
cross join generate_series(0, 3) k
join subjects s on s.name = (array['국어', '영어', '수학', '과학'])[k + 1];

insert into calendar_events (user_id, title, event_date)
select u.id, '일정 ' || n, current_date + (n * 5 - 7)
from users u cross join generate_series(1, 3) n
where u.email like 'loadtest%@rooter.test';

commit;

select
  (select count(*) from users where email like 'loadtest%@rooter.test') as users,
  (select count(*) from plan_tasks pt join daily_plans dp on dp.id = pt.daily_plan_id
     join plan_boards b on b.id = dp.plan_board_id join users u on u.id = b.user_id
    where u.email like 'loadtest%@rooter.test') as tasks;
