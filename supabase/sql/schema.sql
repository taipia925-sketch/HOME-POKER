-- Home Poker: schema completo del database Supabase (tabelle, indici, sicurezza RLS,
-- funzioni, trigger, realtime, storage). Si esegue tutto in un progetto Supabase nuovo,
-- nell'SQL Editor, in quest'ordine.

create extension if not exists pgcrypto with schema extensions;

-- ---------------------------------------------------------------- tabelle
create table public.hpl_accounts (
  id uuid default gen_random_uuid() not null,
  name text not null,
  name_key text not null,
  pin_hash text not null,
  created_at timestamp with time zone default now() not null,
  constraint hpl_accounts_name_key_key UNIQUE (name_key),
  constraint hpl_accounts_pkey PRIMARY KEY (id)
);

create table public.hpl_tables (
  id text not null,
  name text not null,
  pw_hash text not null,
  created_at timestamp with time zone default now() not null,
  cover text,
  constraint hpl_tables_pkey PRIMARY KEY (id)
);

create table public.hpl_account_tables (
  account_id uuid not null,
  table_id text not null,
  player_id text not null,
  last_at timestamp with time zone default now() not null,
  constraint hpl_account_tables_account_id_fkey FOREIGN KEY (account_id) REFERENCES hpl_accounts(id) ON DELETE CASCADE,
  constraint hpl_account_tables_pkey PRIMARY KEY (account_id, table_id),
  constraint hpl_account_tables_table_id_fkey FOREIGN KEY (table_id) REFERENCES hpl_tables(id) ON DELETE CASCADE,
  constraint hpl_account_tables_table_id_player_id_key UNIQUE (table_id, player_id)
);

create table public.hpl_attempts (
  uid uuid not null,
  at timestamp with time zone default now() not null,
  table_id text
);

create table public.hpl_devices (
  uid uuid not null,
  account_id uuid not null,
  linked_at timestamp with time zone default now() not null,
  constraint hpl_devices_account_id_fkey FOREIGN KEY (account_id) REFERENCES hpl_accounts(id) ON DELETE CASCADE,
  constraint hpl_devices_pkey PRIMARY KEY (uid)
);

create table public.hpl_docs (
  path text not null,
  parent text not null,
  data jsonb not null,
  updated_at timestamp with time zone default now() not null,
  constraint hpl_docs_pkey PRIMARY KEY (path)
);

create table public.hpl_members (
  uid uuid not null,
  table_id text not null,
  joined_at timestamp with time zone default now() not null,
  constraint hpl_members_pkey PRIMARY KEY (uid, table_id),
  constraint hpl_members_table_id_fkey FOREIGN KEY (table_id) REFERENCES hpl_tables(id) ON DELETE CASCADE
);

create table public.hpl_pin_fails (
  account_id uuid not null,
  at timestamp with time zone default now() not null
);

create table public.hpl_push_subs (
  endpoint text not null,
  account_id uuid not null,
  p256dh text not null,
  auth text not null,
  created_at timestamp with time zone default now() not null,
  constraint hpl_push_subs_account_id_fkey FOREIGN KEY (account_id) REFERENCES hpl_accounts(id) ON DELETE CASCADE,
  constraint hpl_push_subs_pkey PRIMARY KEY (endpoint)
);

create table public.hpl_vr (
  table_id text not null,
  room_id text not null,
  state jsonb not null,
  v integer default 0 not null,
  updated_at timestamp with time zone default now() not null,
  constraint hpl_vr_pkey PRIMARY KEY (table_id, room_id),
  constraint hpl_vr_table_id_fkey FOREIGN KEY (table_id) REFERENCES hpl_tables(id) ON DELETE CASCADE
);

create table public.hpl_vr_priv (
  table_id text not null,
  room_id text not null,
  priv jsonb default '{}'::jsonb not null,
  constraint hpl_vr_priv_pkey PRIMARY KEY (table_id, room_id),
  constraint hpl_vr_priv_table_id_room_id_fkey FOREIGN KEY (table_id, room_id) REFERENCES hpl_vr(table_id, room_id) ON DELETE CASCADE
);

create table public.hpl_vt (
  table_id text not null,
  state jsonb not null,
  v integer default 0 not null,
  updated_at timestamp with time zone default now() not null,
  constraint hpl_vt_pkey PRIMARY KEY (table_id),
  constraint hpl_vt_table_id_fkey FOREIGN KEY (table_id) REFERENCES hpl_tables(id) ON DELETE CASCADE
);

create table public.hpl_vt_priv (
  table_id text not null,
  priv jsonb default '{}'::jsonb not null,
  constraint hpl_vt_priv_pkey PRIMARY KEY (table_id),
  constraint hpl_vt_priv_table_id_fkey FOREIGN KEY (table_id) REFERENCES hpl_tables(id) ON DELETE CASCADE
);

create table public.hpl_chat (
  id bigint generated always as identity not null,
  table_id text not null,
  author_acct uuid,
  author_name text,
  kind text default 'text'::text not null,
  body jsonb default '{}'::jsonb not null,
  created_at timestamp with time zone default now() not null,
  edited_at timestamp with time zone,
  deleted boolean default false not null,
  constraint hpl_chat_kind_check CHECK ((kind = ANY (ARRAY['text'::text, 'photo'::text, 'poll'::text, 'dice'::text, 'card'::text, 'coin'::text, 'event'::text, 'audio'::text, 'sticker'::text, 'result'::text]))),
  constraint hpl_chat_pkey PRIMARY KEY (id)
);

create table public.hpl_chat_react (
  msg_id bigint not null,
  table_id text not null,
  acct uuid not null,
  emoji text not null,
  created_at timestamp with time zone default now() not null,
  constraint hpl_chat_react_emoji_check CHECK ((char_length(emoji) <= 16)),
  constraint hpl_chat_react_msg_id_fkey FOREIGN KEY (msg_id) REFERENCES hpl_chat(id) ON DELETE CASCADE,
  constraint hpl_chat_react_pkey PRIMARY KEY (msg_id, acct, emoji)
);

create table public.hpl_chat_read (
  table_id text not null,
  acct uuid not null,
  last_id bigint default 0 not null,
  updated_at timestamp with time zone default now() not null,
  constraint hpl_chat_read_pkey PRIMARY KEY (table_id, acct)
);

create table public.hpl_chat_vote (
  msg_id bigint not null,
  table_id text not null,
  acct uuid not null,
  opt integer not null,
  created_at timestamp with time zone default now() not null,
  constraint hpl_chat_vote_msg_id_fkey FOREIGN KEY (msg_id) REFERENCES hpl_chat(id) ON DELETE CASCADE,
  constraint hpl_chat_vote_opt_check CHECK (((opt >= 0) AND (opt <= 9))),
  constraint hpl_chat_vote_pkey PRIMARY KEY (msg_id, acct, opt)
);

-- ---------------------------------------------------------------- funzioni
CREATE OR REPLACE FUNCTION public.hpl_acc()
 RETURNS uuid LANGUAGE sql STABLE SECURITY DEFINER SET search_path TO 'public'
AS $function$
  select account_id from hpl_devices where uid = auth.uid()
$function$;

create table public.hpl_chat_mute (
  table_id text not null,
  acct uuid default hpl_acc() not null,
  until timestamp with time zone,
  constraint hpl_chat_mute_pkey PRIMARY KEY (table_id, acct)
);

CREATE OR REPLACE FUNCTION public.hpl_is_member(tid text)
 RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path TO 'public'
AS $function$
  select exists (select 1 from hpl_members where uid = auth.uid() and table_id = tid)
$function$;

CREATE OR REPLACE FUNCTION public.hpl_is_mgr(p_tid text)
 RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path TO 'public'
AS $function$
  select coalesce((
    select (d.data->'managers') ? at.player_id
    from public.hpl_account_tables at
    left join public.hpl_docs d on d.path = 't/' || p_tid || '/settings/table'
    where at.account_id = public.hpl_acc() and at.table_id = p_tid
    limit 1), false)
$function$;

CREATE OR REPLACE FUNCTION public.hpl_pin_ok(acc uuid, pin text)
 RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path TO 'public', 'extensions'
AS $function$
declare h text;
begin
  if (select count(*) from hpl_pin_fails where account_id = acc and at > now() - interval '10 minutes') >= 8 then
    raise exception 'Troppi PIN sbagliati: riprova tra qualche minuto.';
  end if;
  select pin_hash into h from hpl_accounts where id = acc;
  if h is not null and h = crypt(coalesce(pin, ''), h) then return true; end if;
  insert into hpl_pin_fails (account_id) values (acc);
  return false;
end $function$;

CREATE OR REPLACE FUNCTION public.hpl_adopt_legacy(acc uuid, k text, pin text)
 RETURNS integer LANGUAGE plpgsql SECURITY DEFINER SET search_path TO 'public', 'extensions'
AS $function$
declare r record; n int := 0; tid text; pid text;
begin
  for r in
    select path, data from hpl_docs
    where path like 't/%/players/%'
      and lower(regexp_replace(trim(data->>'name'), '\s+', ' ', 'g')) = k
      and coalesce(data->>'pinHash', '') <> ''
  loop
    tid := split_part(r.path, '/', 2); pid := split_part(r.path, '/', 4);
    continue when r.data->>'pinHash' <> encode(digest('hplpin:' || pid || ':' || coalesce(pin, ''), 'sha256'), 'hex');
    continue when exists (select 1 from hpl_account_tables where table_id = tid and (player_id = pid or account_id = acc));
    continue when not exists (select 1 from hpl_tables where id = tid);
    insert into hpl_account_tables (account_id, table_id, player_id) values (acc, tid, pid);
    update hpl_docs set data = (data - 'pinHash' - 'bio') || jsonb_build_object('acct', acc::text, 'registered', true), updated_at = now() where path = r.path;
    n := n + 1;
  end loop;
  return n;
end $function$;

CREATE OR REPLACE FUNCTION public.hpl_link_device(acc uuid)
 RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path TO 'public'
AS $function$
begin
  delete from hpl_members where uid = auth.uid();
  delete from hpl_devices where uid = auth.uid();
  insert into hpl_devices (uid, account_id) values (auth.uid(), acc);
  insert into hpl_members (uid, table_id)
    select auth.uid(), table_id from hpl_account_tables where account_id = acc
  on conflict do nothing;
end $function$;

CREATE OR REPLACE FUNCTION public.hpl_register(p_name text, p_pin text)
 RETURNS json LANGUAGE plpgsql SECURITY DEFINER SET search_path TO 'public', 'extensions'
AS $function$
declare n text := regexp_replace(trim(coalesce(p_name, '')), '\s+', ' ', 'g'); k text; a uuid;
begin
  if auth.uid() is null then raise exception 'not_signed_in'; end if;
  k := lower(n);
  if length(n) < 2 or length(n) > 24 or coalesce(p_pin, '') !~ '^\d{4,6}$' then return json_build_object('status', 'invalid'); end if;
  if k = 'dealer' then return json_build_object('status', 'reserved'); end if;
  if exists (select 1 from hpl_accounts where name_key = k) then return json_build_object('status', 'exists'); end if;
  insert into hpl_accounts (name, name_key, pin_hash) values (n, k, crypt(p_pin, gen_salt('bf'))) returning id into a;
  perform hpl_adopt_legacy(a, k, p_pin);
  perform hpl_link_device(a);
  return json_build_object('status', 'ok', 'id', a);
end $function$;

CREATE OR REPLACE FUNCTION public.hpl_login(p_name text, p_pin text)
 RETURNS json LANGUAGE plpgsql SECURITY DEFINER SET search_path TO 'public', 'extensions'
AS $function$
declare n text := regexp_replace(trim(coalesce(p_name, '')), '\s+', ' ', 'g'); k text; a uuid; got int;
begin
  if auth.uid() is null then raise exception 'not_signed_in'; end if;
  k := lower(n);
  select id into a from hpl_accounts where name_key = k;
  if a is null then
    if coalesce(p_pin, '') !~ '^\d{4,6}$' or k = 'dealer' then return json_build_object('status', 'not_found'); end if;
    if not exists (select 1 from hpl_docs where path like 't/%/players/%' and coalesce(data->>'pinHash', '') <> ''
                   and lower(regexp_replace(trim(data->>'name'), '\s+', ' ', 'g')) = k) then
      return json_build_object('status', 'not_found');
    end if;
    if (select count(*) from hpl_attempts where uid = auth.uid() and at > now() - interval '10 minutes') >= 8 then
      return json_build_object('status', 'too_many');
    end if;
    if not exists (
      select 1 from hpl_docs
      where path like 't/%/players/%'
        and lower(regexp_replace(trim(data->>'name'), '\s+', ' ', 'g')) = k
        and data->>'pinHash' = encode(digest('hplpin:' || split_part(path, '/', 4) || ':' || p_pin, 'sha256'), 'hex')
    ) then
      insert into hpl_attempts (uid) values (auth.uid());
      return json_build_object('status', 'bad_pin');
    end if;
    select regexp_replace(trim(data->>'name'), '\s+', ' ', 'g') into n from hpl_docs
      where path like 't/%/players/%' and lower(regexp_replace(trim(data->>'name'), '\s+', ' ', 'g')) = k
        and data->>'pinHash' = encode(digest('hplpin:' || split_part(path, '/', 4) || ':' || p_pin, 'sha256'), 'hex')
      limit 1;
    insert into hpl_accounts (name, name_key, pin_hash) values (n, k, crypt(p_pin, gen_salt('bf'))) returning id into a;
    got := hpl_adopt_legacy(a, k, p_pin);
    perform hpl_link_device(a);
    return json_build_object('status', 'ok', 'id', a, 'migrated', got);
  end if;
  if not hpl_pin_ok(a, p_pin) then return json_build_object('status', 'bad_pin'); end if;
  perform hpl_adopt_legacy(a, k, p_pin);
  perform hpl_link_device(a);
  return json_build_object('status', 'ok', 'id', a);
end $function$;

CREATE OR REPLACE FUNCTION public.hpl_logout()
 RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path TO 'public'
AS $function$
begin
  delete from hpl_members where uid = auth.uid();
  delete from hpl_devices where uid = auth.uid();
end $function$;

CREATE OR REPLACE FUNCTION public.hpl_me()
 RETURNS json LANGUAGE plpgsql SECURITY DEFINER SET search_path TO 'public'
AS $function$
declare a uuid := hpl_acc(); r json;
begin
  if a is null then return null; end if;
  insert into hpl_members (uid, table_id)
    select auth.uid(), table_id from hpl_account_tables where account_id = a
  on conflict do nothing;
  select json_build_object('id', x.id, 'name', x.name, 'tables', coalesce((
    select json_agg(json_build_object(
      'id', t.id, 'name', t.name, 'player_id', at.player_id, 'last_at', at.last_at,
      'cover', t.cover,
      'np', (select count(*) from hpl_docs d where d.parent = 't/' || t.id || '/players'),
      'mgr', coalesce((select (d.data->'managers') ? at.player_id
                       from hpl_docs d where d.path = 't/' || t.id || '/settings/table'), false)
    ) order by at.last_at desc)
    from hpl_account_tables at join hpl_tables t on t.id = at.table_id where at.account_id = a), '[]'::json))
  into r from hpl_accounts x where x.id = a;
  return r;
end $function$;

CREATE OR REPLACE FUNCTION public.hpl_change_pin(p_old text, p_new text)
 RETURNS text LANGUAGE plpgsql SECURITY DEFINER SET search_path TO 'public', 'extensions'
AS $function$
declare a uuid := hpl_acc();
begin
  if a is null then raise exception 'not_signed_in'; end if;
  if coalesce(p_new, '') !~ '^\d{4,6}$' then return 'invalid'; end if;
  if not hpl_pin_ok(a, p_old) then return 'bad_pin'; end if;
  update hpl_accounts set pin_hash = crypt(p_new, gen_salt('bf')) where id = a;
  return 'ok';
end $function$;

CREATE OR REPLACE FUNCTION public.hpl_check_pin(p_pin text)
 RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path TO 'public'
AS $function$
declare a uuid := hpl_acc();
begin
  if a is null then raise exception 'not_signed_in'; end if;
  return hpl_pin_ok(a, p_pin);
end $function$;

CREATE OR REPLACE FUNCTION public.hpl_rename(p_name text, p_pin text)
 RETURNS text LANGUAGE plpgsql SECURITY DEFINER SET search_path TO 'public'
AS $function$
declare a uuid := hpl_acc(); n text := regexp_replace(trim(coalesce(p_name, '')), '\s+', ' ', 'g'); k text;
begin
  if a is null then raise exception 'not_signed_in'; end if;
  k := lower(n);
  if length(n) < 2 or length(n) > 24 or k = 'dealer' then return 'invalid'; end if;
  if exists (select 1 from hpl_accounts where name_key = k and id <> a) then return 'exists'; end if;
  if not hpl_pin_ok(a, p_pin) then return 'bad_pin'; end if;
  update hpl_accounts set name = n, name_key = k where id = a;
  update hpl_docs d set data = jsonb_set(d.data, '{name}', to_jsonb(n)), updated_at = now()
    from hpl_account_tables t
    where t.account_id = a and d.path = 't/' || t.table_id || '/players/' || t.player_id;
  return 'ok';
end $function$;

CREATE OR REPLACE FUNCTION public.hpl_delete_account(p_pin text)
 RETURNS text LANGUAGE plpgsql SECURITY DEFINER SET search_path TO 'public'
AS $function$
declare a uuid := hpl_acc();
begin
  if a is null then raise exception 'not_signed_in'; end if;
  if exists (select 1 from hpl_account_tables where account_id = a) then return 'has_tables'; end if;
  if not hpl_pin_ok(a, p_pin) then return 'bad_pin'; end if;
  delete from hpl_members where uid in (select uid from hpl_devices where account_id = a);
  delete from hpl_accounts where id = a;
  return 'ok';
end $function$;

CREATE OR REPLACE FUNCTION public.hpl_create_table(tid text, tname text, pw text)
 RETURNS text LANGUAGE plpgsql SECURITY DEFINER SET search_path TO 'public', 'extensions'
AS $function$
begin
  if auth.uid() is null then raise exception 'not_signed_in'; end if;
  if length(coalesce(pw, '')) < 4 or length(coalesce(tname, '')) < 2 then return 'invalid'; end if;
  if exists (select 1 from hpl_tables where id = tid) then return 'exists'; end if;
  insert into hpl_tables (id, name, pw_hash) values (tid, left(tname, 40), crypt(pw, gen_salt('bf')));
  insert into hpl_members (uid, table_id) values (auth.uid(), tid) on conflict do nothing;
  return 'ok';
end $function$;

CREATE OR REPLACE FUNCTION public.hpl_join_table(tid text, pw text)
 RETURNS json LANGUAGE plpgsql SECURITY DEFINER SET search_path TO 'public', 'extensions'
AS $function$
declare t hpl_tables; ok boolean;
begin
  if auth.uid() is null then raise exception 'not_signed_in'; end if;
  if (select count(*) from hpl_attempts where uid = auth.uid() and at > now() - interval '10 minutes') >= 8 then
    return json_build_object('status', 'too_many');
  end if;
  if (select count(*) from hpl_attempts where table_id = tid and at > now() - interval '10 minutes') >= 30 then
    return json_build_object('status', 'too_many');
  end if;
  select * into t from hpl_tables where id = tid;
  if not found then return json_build_object('status', 'not_found'); end if;
  if t.pw_hash like 'sha:%' then
    ok := substr(t.pw_hash, 5) = encode(digest('hpltp:' || tid || ':' || pw, 'sha256'), 'hex');
    if ok then update hpl_tables set pw_hash = crypt(pw, gen_salt('bf')) where id = tid; end if;
  else
    ok := t.pw_hash = crypt(pw, t.pw_hash);
  end if;
  if not ok then
    insert into hpl_attempts (uid, table_id) values (auth.uid(), tid);
    return json_build_object('status', 'bad_password');
  end if;
  insert into hpl_members (uid, table_id) values (auth.uid(), tid) on conflict do nothing;
  return json_build_object('status', 'ok', 'name', t.name);
end $function$;

CREATE OR REPLACE FUNCTION public.hpl_my_table(tid text)
 RETURNS json LANGUAGE sql STABLE SECURITY DEFINER SET search_path TO 'public'
AS $function$
  select case when exists (select 1 from hpl_members where uid = auth.uid() and table_id = tid)
    then (select json_build_object('status', 'ok', 'name', name) from hpl_tables where id = tid)
    else json_build_object('status', 'no') end
$function$;

CREATE OR REPLACE FUNCTION public.hpl_link_table(p_tid text, p_player text, p_old_pin text DEFAULT NULL::text)
 RETURNS text LANGUAGE plpgsql SECURITY DEFINER SET search_path TO 'public', 'extensions'
AS $function$
declare a uuid := hpl_acc(); d jsonb; pth text := 't/' || p_tid || '/players/' || p_player;
begin
  if a is null then raise exception 'not_signed_in'; end if;
  if not hpl_is_member(p_tid) then return 'not_member'; end if;
  if exists (select 1 from hpl_account_tables where table_id = p_tid and player_id = p_player and account_id <> a) then return 'taken'; end if;
  select data into d from hpl_docs where path = pth;
  if d is not null and coalesce(d->>'pinHash', '') <> '' then
    if p_old_pin is null then return 'need_pin'; end if;
    if (select count(*) from hpl_pin_fails where account_id = a and at > now() - interval '10 minutes') >= 8 then return 'too_many'; end if;
    if d->>'pinHash' <> encode(digest('hplpin:' || p_player || ':' || p_old_pin, 'sha256'), 'hex') then
      insert into hpl_pin_fails (account_id) values (a);
      return 'bad_pin';
    end if;
  end if;
  if d is not null then
    update hpl_docs set data = (data - 'pinHash' - 'bio') || jsonb_build_object('acct', a::text, 'registered', true), updated_at = now() where path = pth;
  end if;
  delete from hpl_account_tables where account_id = a and table_id = p_tid;
  insert into hpl_account_tables (account_id, table_id, player_id) values (a, p_tid, p_player);
  return 'ok';
end $function$;

CREATE OR REPLACE FUNCTION public.hpl_leave_table(p_tid text)
 RETURNS text LANGUAGE plpgsql SECURITY DEFINER SET search_path TO 'public'
AS $function$
declare a uuid := hpl_acc(); pid text;
begin
  if a is null then raise exception 'not_signed_in'; end if;
  delete from hpl_account_tables where account_id = a and table_id = p_tid returning player_id into pid;
  if pid is not null then
    update hpl_docs set data = data - 'acct' - 'pinHash' - 'bio' || '{"registered":false}'::jsonb where path = 't/' || p_tid || '/players/' || pid;
  end if;
  delete from hpl_members where table_id = p_tid and uid in (select uid from hpl_devices where account_id = a);
  return 'ok';
end $function$;

CREATE OR REPLACE FUNCTION public.hpl_kick(p_tid text, p_player text)
 RETURNS text LANGUAGE plpgsql SECURITY DEFINER SET search_path TO 'public'
AS $function$
declare who uuid;
begin
  if not hpl_is_member(p_tid) then return 'not_member'; end if;
  if not hpl_is_mgr(p_tid) and not exists (select 1 from hpl_account_tables where table_id = p_tid and player_id = p_player and account_id = hpl_acc()) then
    return 'not_manager';
  end if;
  delete from hpl_account_tables where table_id = p_tid and player_id = p_player returning account_id into who;
  if who is not null then
    delete from hpl_members where table_id = p_tid and uid in (select uid from hpl_devices where account_id = who);
  end if;
  update hpl_docs set data = (data - 'acct' - 'pinHash' - 'bio') || jsonb_build_object('registered', false) where path = 't/' || p_tid || '/players/' || p_player;
  return 'ok';
end $function$;

CREATE OR REPLACE FUNCTION public.hpl_delete_table(p_tid text, p_pin text)
 RETURNS text LANGUAGE plpgsql SECURITY DEFINER SET search_path TO 'public', 'extensions'
AS $function$
declare a uuid := hpl_acc();
begin
  if a is null then return 'not_signed_in'; end if;
  if not hpl_pin_ok(a, p_pin) then return 'bad_pin'; end if;
  if not hpl_is_mgr(p_tid) then return 'not_manager'; end if;
  delete from hpl_docs where path like 't/' || p_tid || '/%';
  begin delete from hpl_vr_priv where table_id = p_tid; exception when undefined_table then null; end;
  begin delete from hpl_vr      where table_id = p_tid; exception when undefined_table then null; end;
  begin delete from hpl_vt_priv where table_id = p_tid; exception when undefined_table then null; end;
  begin delete from hpl_vt      where table_id = p_tid; exception when undefined_table then null; end;
  delete from hpl_account_tables where table_id = p_tid;
  delete from hpl_members        where table_id = p_tid;
  delete from hpl_tables         where id = p_tid;
  return 'ok';
end $function$;

CREATE OR REPLACE FUNCTION public.hpl_set_cover(p_tid text, p_cover text)
 RETURNS text LANGUAGE plpgsql SECURITY DEFINER SET search_path TO 'public'
AS $function$
begin
  if hpl_acc() is null then return 'not_signed_in'; end if;
  if not hpl_is_mgr(p_tid) then return 'not_manager'; end if;
  if length(coalesce(p_cover, '')) > 400000 then return 'too_big'; end if;
  update hpl_tables set cover = nullif(p_cover, '') where id = p_tid;
  return 'ok';
end $function$;

CREATE OR REPLACE FUNCTION public.hpl_touch_table(p_tid text)
 RETURNS void LANGUAGE sql SECURITY DEFINER SET search_path TO 'public'
AS $function$
  update hpl_account_tables set last_at = now() where account_id = hpl_acc() and table_id = p_tid
$function$;

CREATE OR REPLACE FUNCTION public.hpl_save_push(p_endpoint text, p_p256dh text, p_auth text)
 RETURNS text LANGUAGE plpgsql SECURITY DEFINER SET search_path TO 'public'
AS $function$
declare a uuid := hpl_acc();
begin
  if a is null then raise exception 'not_signed_in'; end if;
  insert into hpl_push_subs (endpoint, account_id, p256dh, auth) values (p_endpoint, a, p_p256dh, p_auth)
  on conflict (endpoint) do update set account_id = excluded.account_id, p256dh = excluded.p256dh, auth = excluded.auth;
  return 'ok';
end $function$;

CREATE OR REPLACE FUNCTION public.hpl_delete_push(p_endpoint text)
 RETURNS void LANGUAGE sql SECURITY DEFINER SET search_path TO 'public'
AS $function$
  delete from hpl_push_subs where endpoint = p_endpoint and account_id = hpl_acc()
$function$;

-- tavoli virtuali (usate dalla funzione edge hpl-holdem)
CREATE OR REPLACE FUNCTION public.hpl_vr_ctx(p_uid uuid, p_tid text, p_rid text)
 RETURNS json LANGUAGE sql STABLE SECURITY DEFINER SET search_path TO 'public'
AS $function$
  with d as (select (select account_id from hpl_devices where uid = p_uid) as acc)
  select json_build_object(
    'member', exists (select 1 from hpl_members where uid = p_uid and table_id = p_tid),
    'acct',   d.acc,
    'pid',    (select player_id from hpl_account_tables where account_id = d.acc and table_id = p_tid),
    'name',   (select name from hpl_accounts where id = d.acc),
    'state',  (select state from hpl_vr where table_id = p_tid and room_id = p_rid),
    'v',      (select v from hpl_vr where table_id = p_tid and room_id = p_rid),
    'priv',   (select priv from hpl_vr_priv where table_id = p_tid and room_id = p_rid),
    'rooms',  (select count(*) from hpl_vr where table_id = p_tid),
    'mgr',    coalesce((select (dd.data->'managers') ? (select player_id from hpl_account_tables where account_id = d.acc and table_id = p_tid)
                        from hpl_docs dd where dd.path = 't/' || p_tid || '/settings/table'), false))
  from d
$function$;

CREATE OR REPLACE FUNCTION public.hpl_vr_del(p_tid text, p_rid text)
 RETURNS void LANGUAGE sql SECURITY DEFINER SET search_path TO 'public'
AS $function$
  delete from hpl_vr where table_id = p_tid and room_id = p_rid
$function$;

CREATE OR REPLACE FUNCTION public.hpl_vr_gc(p_tid text)
 RETURNS void LANGUAGE sql SECURITY DEFINER SET search_path TO 'public'
AS $function$
  delete from hpl_vr where table_id = p_tid and (
    (coalesce((state->>'closed')::boolean, false) and updated_at < now() - interval '90 seconds')
    or updated_at < now() - interval '12 hours')
$function$;

CREATE OR REPLACE FUNCTION public.hpl_vr_save(p_tid text, p_rid text, p_v integer, p_state jsonb, p_priv jsonb)
 RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path TO 'public'
AS $function$
begin
  if p_v < 0 then
    insert into hpl_vr (table_id, room_id, state, v) values (p_tid, p_rid, p_state, 0) on conflict (table_id, room_id) do nothing;
    if not found then return false; end if;
  else
    update hpl_vr set state = p_state, v = p_v + 1, updated_at = now() where table_id = p_tid and room_id = p_rid and v = p_v;
    if not found then return false; end if;
  end if;
  insert into hpl_vr_priv (table_id, room_id, priv) values (p_tid, p_rid, p_priv)
    on conflict (table_id, room_id) do update set priv = excluded.priv;
  return true;
end $function$;

CREATE OR REPLACE FUNCTION public.hpl_vt_load(p_tid text)
 RETURNS json LANGUAGE sql STABLE SECURITY DEFINER SET search_path TO 'public'
AS $function$
  select json_build_object(
    'state', (select state from hpl_vt where table_id = p_tid),
    'v',     (select v from hpl_vt where table_id = p_tid),
    'priv',  (select priv from hpl_vt_priv where table_id = p_tid))
$function$;

CREATE OR REPLACE FUNCTION public.hpl_vt_save(p_tid text, p_v integer, p_state jsonb, p_priv jsonb)
 RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path TO 'public'
AS $function$
begin
  if p_v < 0 then
    insert into hpl_vt (table_id, state, v) values (p_tid, p_state, 0) on conflict (table_id) do nothing;
    if not found then return false; end if;
  else
    update hpl_vt set state = p_state, v = p_v + 1, updated_at = now() where table_id = p_tid and v = p_v;
    if not found then return false; end if;
  end if;
  insert into hpl_vt_priv (table_id, priv) values (p_tid, p_priv)
    on conflict (table_id) do update set priv = excluded.priv;
  return true;
end $function$;

-- trigger della chat e dei documenti
CREATE OR REPLACE FUNCTION public.hpl_chat_acct()
 RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path TO 'public'
AS $function$
declare a uuid := hpl_acc();
begin
  if a is null then
    if coalesce(auth.role(),'') = 'service_role' and new.acct is not null then return new; end if;
    raise exception 'not_signed_in';
  end if;
  new.acct := a;
  return new;
end $function$;

CREATE OR REPLACE FUNCTION public.hpl_chat_stamp()
 RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path TO 'public'
AS $function$
declare a uuid := hpl_acc();
begin
  if a is null then
    if coalesce(auth.role(),'') = 'service_role' and tg_op = 'INSERT' and new.author_acct is not null then
      new.author_name := (select name from hpl_accounts where id = new.author_acct);
      new.created_at := now(); new.deleted := false; new.edited_at := null;
      if pg_column_size(new.body) > 20000 then raise exception 'too_big'; end if;
      return new;
    end if;
    raise exception 'not_signed_in';
  end if;
  if tg_op = 'INSERT' then
    new.author_acct := a;
    new.author_name := (select name from hpl_accounts where id = a);
    new.created_at := now(); new.deleted := false; new.edited_at := null;
    if pg_column_size(new.body) > 20000 then raise exception 'too_big'; end if;
  else
    if old.author_acct is distinct from a then raise exception 'not_author'; end if;
    new.author_acct := old.author_acct; new.author_name := old.author_name;
    new.table_id := old.table_id; new.kind := old.kind; new.created_at := old.created_at;
    if new.deleted then new.body := '{}'::jsonb; else new.edited_at := now(); end if;
  end if;
  return new;
end $function$;

CREATE OR REPLACE FUNCTION public.hpl_docs_guard()
 RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path TO 'public'
AS $function$
declare tid text;
begin
  if coalesce(auth.role(),'') = 'service_role' or auth.uid() is null then return new; end if;
  if new.path like 't/%/settings/table' then
    tid := split_part(new.path, '/', 2);
    if (old.data->'managers') is distinct from (new.data->'managers')
       and jsonb_array_length(coalesce(old.data->'managers','[]'::jsonb)) > 0
       and not hpl_is_mgr(tid) then
      raise exception 'Solo un gestore può cambiare i gestori del tavolo';
    end if;
  end if;
  return new;
end $function$;

CREATE OR REPLACE FUNCTION public.hpl_docs_guard_del()
 RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path TO 'public'
AS $function$
begin
  if coalesce(auth.role(),'') = 'service_role' or auth.uid() is null then return old; end if;
  if old.path like 't/%/settings/table'
     and jsonb_array_length(coalesce(old.data->'managers','[]'::jsonb)) > 0
     and not hpl_is_mgr(split_part(old.path, '/', 2)) then
    raise exception 'Solo un gestore può cancellare le impostazioni del tavolo';
  end if;
  return old;
end $function$;

CREATE TRIGGER hpl_chat_react_acct BEFORE INSERT ON public.hpl_chat_react FOR EACH ROW EXECUTE FUNCTION hpl_chat_acct();
CREATE TRIGGER hpl_chat_read_acct BEFORE INSERT OR UPDATE ON public.hpl_chat_read FOR EACH ROW EXECUTE FUNCTION hpl_chat_acct();
CREATE TRIGGER hpl_chat_stamp BEFORE INSERT OR UPDATE ON public.hpl_chat FOR EACH ROW EXECUTE FUNCTION hpl_chat_stamp();
CREATE TRIGGER hpl_chat_vote_acct BEFORE INSERT ON public.hpl_chat_vote FOR EACH ROW EXECUTE FUNCTION hpl_chat_acct();
CREATE TRIGGER hpl_docs_guard BEFORE UPDATE ON public.hpl_docs FOR EACH ROW EXECUTE FUNCTION hpl_docs_guard();
CREATE TRIGGER hpl_docs_guard_del BEFORE DELETE ON public.hpl_docs FOR EACH ROW EXECUTE FUNCTION hpl_docs_guard_del();

-- ---------------------------------------------------------------- indici
CREATE INDEX hpl_attempts_tid_at ON public.hpl_attempts USING btree (table_id, at);
CREATE INDEX hpl_chat_react_tid ON public.hpl_chat_react USING btree (table_id);
CREATE INDEX hpl_chat_tid_id ON public.hpl_chat USING btree (table_id, id DESC);
CREATE INDEX hpl_chat_vote_tid ON public.hpl_chat_vote USING btree (table_id);
CREATE INDEX hpl_docs_parent_idx ON public.hpl_docs USING btree (parent);

-- ---------------------------------------------------------------- sicurezza (RLS)
alter table public.hpl_account_tables enable row level security;
alter table public.hpl_accounts enable row level security;
alter table public.hpl_attempts enable row level security;
alter table public.hpl_chat enable row level security;
alter table public.hpl_chat_mute enable row level security;
alter table public.hpl_chat_react enable row level security;
alter table public.hpl_chat_read enable row level security;
alter table public.hpl_chat_vote enable row level security;
alter table public.hpl_devices enable row level security;
alter table public.hpl_docs enable row level security;
alter table public.hpl_members enable row level security;
alter table public.hpl_pin_fails enable row level security;
alter table public.hpl_push_subs enable row level security;
alter table public.hpl_tables enable row level security;
alter table public.hpl_vr enable row level security;
alter table public.hpl_vr_priv enable row level security;
alter table public.hpl_vt enable row level security;
alter table public.hpl_vt_priv enable row level security;

create policy "hpl_member_rw" on public.hpl_docs as PERMISSIVE for ALL to authenticated using (((path ~~ 't/%'::text) AND hpl_is_member(split_part(path, '/'::text, 2)))) with check (((path ~~ 't/%'::text) AND hpl_is_member(split_part(path, '/'::text, 2))));
create policy "hpl_vr_read" on public.hpl_vr as PERMISSIVE for SELECT to authenticated using (hpl_is_member(table_id));
create policy "hpl_vt_read" on public.hpl_vt as PERMISSIVE for SELECT to authenticated using (hpl_is_member(table_id));
create policy "hpl_chat_ins_p" on public.hpl_chat as PERMISSIVE for INSERT to authenticated with check (hpl_is_member(table_id));
create policy "hpl_chat_read_p" on public.hpl_chat as PERMISSIVE for SELECT to authenticated using (hpl_is_member(table_id));
create policy "hpl_chat_upd_p" on public.hpl_chat as PERMISSIVE for UPDATE to authenticated using ((hpl_is_member(table_id) AND (author_acct = hpl_acc()))) with check (hpl_is_member(table_id));
create policy "hpl_chat_mute_del" on public.hpl_chat_mute as PERMISSIVE for DELETE to authenticated using ((acct = hpl_acc()));
create policy "hpl_chat_mute_ins" on public.hpl_chat_mute as PERMISSIVE for INSERT to authenticated with check (((acct = hpl_acc()) AND hpl_is_member(table_id)));
create policy "hpl_chat_mute_sel" on public.hpl_chat_mute as PERMISSIVE for SELECT to authenticated using ((acct = hpl_acc()));
create policy "hpl_chat_mute_upd" on public.hpl_chat_mute as PERMISSIVE for UPDATE to authenticated using ((acct = hpl_acc())) with check ((acct = hpl_acc()));
create policy "hpl_chat_react_del" on public.hpl_chat_react as PERMISSIVE for DELETE to authenticated using ((acct = hpl_acc()));
create policy "hpl_chat_react_ins" on public.hpl_chat_react as PERMISSIVE for INSERT to authenticated with check ((hpl_is_member(table_id) AND (EXISTS ( SELECT 1 FROM hpl_chat c WHERE ((c.id = hpl_chat_react.msg_id) AND (c.table_id = hpl_chat_react.table_id))))));
create policy "hpl_chat_react_sel" on public.hpl_chat_react as PERMISSIVE for SELECT to authenticated using (hpl_is_member(table_id));
create policy "hpl_chat_rd_ins" on public.hpl_chat_read as PERMISSIVE for INSERT to authenticated with check (hpl_is_member(table_id));
create policy "hpl_chat_rd_sel" on public.hpl_chat_read as PERMISSIVE for SELECT to authenticated using (hpl_is_member(table_id));
create policy "hpl_chat_rd_upd" on public.hpl_chat_read as PERMISSIVE for UPDATE to authenticated using ((acct = hpl_acc())) with check (hpl_is_member(table_id));
create policy "hpl_chat_vote_del" on public.hpl_chat_vote as PERMISSIVE for DELETE to authenticated using ((acct = hpl_acc()));
create policy "hpl_chat_vote_ins" on public.hpl_chat_vote as PERMISSIVE for INSERT to authenticated with check ((hpl_is_member(table_id) AND (EXISTS ( SELECT 1 FROM hpl_chat c WHERE ((c.id = hpl_chat_vote.msg_id) AND (c.table_id = hpl_chat_vote.table_id) AND (c.kind = 'poll'::text) AND (NOT c.deleted) AND (COALESCE(((c.body ->> 'closed'::text))::boolean, false) = false))))));
create policy "hpl_chat_vote_sel" on public.hpl_chat_vote as PERMISSIVE for SELECT to authenticated using (hpl_is_member(table_id));

-- funzioni interne: nessuno le chiama dall'app
revoke execute on function public.hpl_link_device(uuid) from public, anon, authenticated;
revoke execute on function public.hpl_pin_ok(uuid, text) from public, anon, authenticated;
revoke execute on function public.hpl_adopt_legacy(uuid, text, text) from public, anon, authenticated;
revoke execute on function public.hpl_delete_table(text, text) from anon;
revoke execute on function public.hpl_set_cover(text, text) from anon;
revoke execute on function public.hpl_is_mgr(text) from anon;

-- ---------------------------------------------------------------- realtime
alter publication supabase_realtime add table public.hpl_chat;
alter publication supabase_realtime add table public.hpl_chat_react;
alter publication supabase_realtime add table public.hpl_chat_read;
alter publication supabase_realtime add table public.hpl_chat_vote;
alter publication supabase_realtime add table public.hpl_docs;
alter publication supabase_realtime add table public.hpl_vr;
alter publication supabase_realtime add table public.hpl_vt;

-- ---------------------------------------------------------------- storage (foto e vocali della chat)
insert into storage.buckets (id,name,public,file_size_limit,allowed_mime_types) values ('hpl-chat','hpl-chat',false,6291456,'{image/jpeg,image/png,image/webp,image/gif,audio/webm,audio/mp4,audio/aac,audio/mpeg,audio/ogg,audio/x-m4a}'::text[]);
create policy "hpl_chat_img_ins" on storage.objects as PERMISSIVE for INSERT to authenticated with check (((bucket_id = 'hpl-chat'::text) AND hpl_is_member(split_part(name, '/'::text, 1))));
create policy "hpl_chat_img_sel" on storage.objects as PERMISSIVE for SELECT to authenticated using (((bucket_id = 'hpl-chat'::text) AND hpl_is_member(split_part(name, '/'::text, 1))));
