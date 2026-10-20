-- Home Poker · correzioni di sicurezza del 20/10/2026
-- Da incollare in Supabase → SQL Editor → Run (una volta sola).

-- 1) Solo un gestore può togliere un giocatore dal tavolo (o ognuno può togliere sé stesso)
create or replace function public.hpl_kick(p_tid text, p_player text) returns text language plpgsql security definer set search_path to 'public' as $function$
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

-- 2) Le impostazioni del tavolo (con l'elenco dei gestori) le può cancellare solo un gestore
create or replace function public.hpl_docs_guard_del() returns trigger language plpgsql security definer set search_path to 'public' as $function$
begin
  if coalesce(auth.role(),'') = 'service_role' or auth.uid() is null then return old; end if;
  if old.path like 't/%/settings/table'
     and jsonb_array_length(coalesce(old.data->'managers','[]'::jsonb)) > 0
     and not hpl_is_mgr(split_part(old.path, '/', 2)) then
    raise exception 'Solo un gestore può cancellare le impostazioni del tavolo';
  end if;
  return old;
end $function$;
revoke execute on function public.hpl_docs_guard_del() from public, anon, authenticated;
create or replace trigger hpl_docs_guard_del before delete on public.hpl_docs for each row execute function public.hpl_docs_guard_del();
