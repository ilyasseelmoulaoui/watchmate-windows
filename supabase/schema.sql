-- WatchMate : synchronisation entre appareils (bibliothèque et progression, modules, réglages).
-- À exécuter une fois dans Supabase : SQL Editor → New query → coller ce fichier → Run.
-- Réexécutable sans risque (create … if not exists / or replace).

create sequence if not exists public.sync_seq;

-- Un élément synchronisé par ligne. data = null : élément supprimé (pierre tombale).
-- updated_at : heure de la modification sur l'appareil (ms) ; la plus récente l'emporte.
-- seq : ordre des écritures sur le serveur, pour ne récupérer que les nouveautés.
create table if not exists public.sync_items (
  user_id    uuid    not null default auth.uid() references auth.users (id) on delete cascade,
  kind       text    not null check (kind in ('library', 'module', 'settings')),
  key        text    not null check (char_length(key) between 1 and 2048),
  data       jsonb   check (data is null or octet_length(data::text) <= 262144),
  updated_at bigint  not null,
  seq        bigint  not null default nextval('public.sync_seq'),
  primary key (user_id, kind, key)
);

create index if not exists sync_items_user_seq on public.sync_items (user_id, seq);

-- Chaque compte ne voit et n'écrit que ses propres lignes.
alter table public.sync_items enable row level security;

drop policy if exists sync_items_select_own on public.sync_items;
create policy sync_items_select_own on public.sync_items
  for select to authenticated using (user_id = auth.uid());

drop policy if exists sync_items_insert_own on public.sync_items;
create policy sync_items_insert_own on public.sync_items
  for insert to authenticated with check (user_id = auth.uid());

drop policy if exists sync_items_update_own on public.sync_items;
create policy sync_items_update_own on public.sync_items
  for update to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());

revoke all on public.sync_items from anon;
grant select, insert, update on public.sync_items to authenticated;
grant usage on sequence public.sync_seq to authenticated;

-- Envoi d'éléments : une écriture n'est retenue que si elle est plus récente que celle du serveur.
create or replace function public.sync_push(items jsonb)
returns bigint
language plpgsql
security invoker
set search_path = public
as $$
declare
  uid uuid := auth.uid();
  last_seq bigint;
begin
  if uid is null then
    raise exception 'not authenticated' using errcode = '28000';
  end if;
  if jsonb_typeof(items) <> 'array' or jsonb_array_length(items) > 500 then
    raise exception 'items must be an array of at most 500 elements' using errcode = '22023';
  end if;

  insert into public.sync_items (user_id, kind, key, data, updated_at)
  select uid, i ->> 'kind', i ->> 'key', nullif(i -> 'data', 'null'::jsonb), (i ->> 'updated_at')::bigint
  from jsonb_array_elements(items) as i
  on conflict (user_id, kind, key) do update
    set data = excluded.data,
        updated_at = excluded.updated_at,
        seq = nextval('public.sync_seq')
    where excluded.updated_at > public.sync_items.updated_at;

  select coalesce(max(seq), 0) into last_seq from public.sync_items where user_id = uid;
  return last_seq;
end;
$$;

revoke all on function public.sync_push(jsonb) from public, anon;
grant execute on function public.sync_push(jsonb) to authenticated;

-- Suppression du compte par son titulaire : le compte et toutes ses données (cascade).
create or replace function public.delete_my_account()
returns void
language plpgsql
security definer
set search_path = public, auth
as $$
begin
  if auth.uid() is null then
    raise exception 'not authenticated' using errcode = '28000';
  end if;
  delete from auth.users where id = auth.uid();
end;
$$;

revoke all on function public.delete_my_account() from public, anon;
grant execute on function public.delete_my_account() to authenticated;
