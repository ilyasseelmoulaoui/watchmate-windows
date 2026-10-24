-- WatchMate : regarder ensemble (salles et droits sur les canaux Realtime).
-- À exécuter une fois dans Supabase : SQL Editor → New query → coller ce fichier → Run.
-- Réexécutable sans risque. Indépendant de schema.sql.
--
-- Aucune vidéo ne passe par ici : les membres d'une salle s'échangent seulement l'état de lecture
-- (titre, épisode, lecture/pause, position) sur le canal Realtime privé « party:<code> ».

create table if not exists public.party_rooms (
  code       text        primary key check (code ~ '^[A-HJ-NP-Z2-9]{8}$'),
  host       uuid        not null references auth.users (id) on delete cascade,
  created_at timestamptz not null default now()
);

create table if not exists public.party_members (
  code      text        not null references public.party_rooms (code) on delete cascade,
  user_id   uuid        not null references auth.users (id) on delete cascade,
  joined_at timestamptz not null default now(),
  primary key (code, user_id)
);

create index if not exists party_members_user on public.party_members (user_id);
create index if not exists party_rooms_host on public.party_rooms (host);

-- Lecture et écriture uniquement par les fonctions ci-dessous ; chacun ne voit que ses propres appartenances
-- (nécessaire aux règles du canal Realtime).
alter table public.party_rooms enable row level security;
alter table public.party_members enable row level security;

drop policy if exists party_members_select_own on public.party_members;
create policy party_members_select_own on public.party_members
  for select to authenticated using (user_id = auth.uid());

revoke all on public.party_rooms from anon, authenticated;
revoke all on public.party_members from anon, authenticated;
grant select on public.party_members to authenticated;

-- Crée une salle : code de 8 caractères tirés au hasard (40 bits, sans I, O, 0, 1), valable 24 h.
create or replace function public.party_create()
returns text
language plpgsql
security definer
set search_path = public
as $$
declare
  uid uuid := auth.uid();
  alphabet constant text := 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  -- Octets entièrement aléatoires d'un UUID v4 (le 6e et le 8e portent la version et la variante).
  positions constant int[] := array[0, 1, 2, 3, 4, 5, 7, 9];
  new_code text;
  bytes bytea;
  i int;
begin
  if uid is null then
    raise exception 'not authenticated' using errcode = '28000';
  end if;

  delete from party_rooms where created_at < now() - interval '24 hours';
  -- Au plus 5 salles ouvertes par compte : les plus anciennes sont fermées.
  delete from party_rooms
  where host = uid
    and code not in (select code from party_rooms where host = uid order by created_at desc limit 4);

  loop
    bytes := uuid_send(gen_random_uuid());
    new_code := '';
    foreach i in array positions loop
      new_code := new_code || substr(alphabet, (get_byte(bytes, i) % 32) + 1, 1);
    end loop;
    begin
      insert into party_rooms (code, host) values (new_code, uid);
      exit;
    exception when unique_violation then
      -- Code déjà pris : on en tire un autre.
    end;
  end loop;

  insert into party_members (code, user_id) values (new_code, uid);
  return new_code;
end;
$$;

-- Rejoint une salle : { status: ok | not_found | full, host }.
create or replace function public.party_join(p_code text)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  uid uuid := auth.uid();
  room party_rooms;
  members int;
begin
  if uid is null then
    raise exception 'not authenticated' using errcode = '28000';
  end if;

  select * into room from party_rooms
  where code = upper(p_code) and created_at > now() - interval '24 hours';
  if not found then
    return jsonb_build_object('status', 'not_found');
  end if;

  if not exists (select 1 from party_members where code = room.code and user_id = uid) then
    select count(*) into members from party_members where code = room.code;
    if members >= 20 then
      return jsonb_build_object('status', 'full');
    end if;
    insert into party_members (code, user_id) values (room.code, uid);
  end if;

  return jsonb_build_object('status', 'ok', 'host', room.host);
end;
$$;

-- Quitte une salle ; la salle disparaît avec son dernier membre.
create or replace function public.party_leave(p_code text)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  uid uuid := auth.uid();
begin
  if uid is null then
    raise exception 'not authenticated' using errcode = '28000';
  end if;
  delete from party_members where code = upper(p_code) and user_id = uid;
  delete from party_rooms r
  where r.code = upper(p_code)
    and not exists (select 1 from party_members m where m.code = r.code);
end;
$$;

revoke all on function public.party_create() from public, anon;
revoke all on function public.party_join(text) from public, anon;
revoke all on function public.party_leave(text) from public, anon;
grant execute on function public.party_create() to authenticated;
grant execute on function public.party_join(text) to authenticated;
grant execute on function public.party_leave(text) to authenticated;

-- Canal Realtime privé « party:<code> » : seuls les membres de la salle peuvent lire et écrire
-- (diffusion et présence).
drop policy if exists party_channel_read on realtime.messages;
create policy party_channel_read on realtime.messages
  for select to authenticated
  using (
    realtime.messages.extension in ('broadcast', 'presence')
    and exists (
      select 1 from public.party_members m
      where m.user_id = (select auth.uid())
        and 'party:' || m.code = (select realtime.topic())
    )
  );

drop policy if exists party_channel_write on realtime.messages;
create policy party_channel_write on realtime.messages
  for insert to authenticated
  with check (
    realtime.messages.extension in ('broadcast', 'presence')
    and exists (
      select 1 from public.party_members m
      where m.user_id = (select auth.uid())
        and 'party:' || m.code = (select realtime.topic())
    )
  );

-- Invitations in-app par e-mail (pas de mail : l'invité voit une notif dans l'app s'il a un compte).
create table if not exists public.party_invites (
  id         uuid        primary key default gen_random_uuid(),
  code       text        not null references public.party_rooms (code) on delete cascade,
  from_user  uuid        not null references auth.users (id) on delete cascade,
  to_user    uuid        not null references auth.users (id) on delete cascade,
  created_at timestamptz not null default now(),
  unique (code, to_user)
);

create index if not exists party_invites_to_user on public.party_invites (to_user);

alter table public.party_invites enable row level security;
drop policy if exists party_invites_select_own on public.party_invites;
create policy party_invites_select_own on public.party_invites
  for select to authenticated using (to_user = auth.uid());

revoke all on public.party_invites from anon, authenticated;
grant select on public.party_invites to authenticated;

-- Invite un compte WatchMate par e-mail. { status: ok | not_found | not_member | self | invalid }.
create or replace function public.party_invite(p_code text, p_email text)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  uid uuid := auth.uid();
  room_code text := upper(trim(p_code));
  target uuid;
  mail text := lower(trim(p_email));
begin
  if uid is null then
    raise exception 'not authenticated' using errcode = '28000';
  end if;
  if mail is null or mail !~ '^[^@\s]+@[^@\s]+\.[^@\s]+$' then
    return jsonb_build_object('status', 'invalid');
  end if;
  if not exists (
    select 1 from party_rooms r
    where r.code = room_code and r.created_at > now() - interval '24 hours'
  ) then
    return jsonb_build_object('status', 'not_found');
  end if;
  if not exists (select 1 from party_members where code = room_code and user_id = uid) then
    return jsonb_build_object('status', 'not_member');
  end if;

  select u.id into target from auth.users u where lower(u.email) = mail limit 1;
  if target is null then
    return jsonb_build_object('status', 'not_found');
  end if;
  if target = uid then
    return jsonb_build_object('status', 'self');
  end if;

  insert into party_invites (code, from_user, to_user)
  values (room_code, uid, target)
  on conflict (code, to_user) do update
    set from_user = excluded.from_user, created_at = now();

  return jsonb_build_object('status', 'ok');
end;
$$;

-- Invitations en attente pour l'utilisateur connecté (salles encore valides).
create or replace function public.party_pending_invites()
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  uid uuid := auth.uid();
begin
  if uid is null then
    raise exception 'not authenticated' using errcode = '28000';
  end if;
  return coalesce((
    select jsonb_agg(jsonb_build_object(
      'id', i.id,
      'code', i.code,
      'from', split_part(coalesce(u.email, '?'), '@', 1),
      'created_at', (extract(epoch from i.created_at) * 1000)::bigint
    ) order by i.created_at desc)
    from party_invites i
    join party_rooms r on r.code = i.code and r.created_at > now() - interval '24 hours'
    left join auth.users u on u.id = i.from_user
    where i.to_user = uid
  ), '[]'::jsonb);
end;
$$;

-- Refuse ou efface une invitation (après acceptation, le client appelle aussi party_join).
create or replace function public.party_dismiss_invite(p_id uuid)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  uid uuid := auth.uid();
begin
  if uid is null then
    raise exception 'not authenticated' using errcode = '28000';
  end if;
  delete from party_invites where id = p_id and to_user = uid;
end;
$$;

revoke all on function public.party_invite(text, text) from public, anon;
revoke all on function public.party_pending_invites() from public, anon;
revoke all on function public.party_dismiss_invite(uuid) from public, anon;
grant execute on function public.party_invite(text, text) to authenticated;
grant execute on function public.party_pending_invites() to authenticated;
grant execute on function public.party_dismiss_invite(uuid) to authenticated;
