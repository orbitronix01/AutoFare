-- AutoFare — take a phone's access away from the portal.
--
-- 0002 deliberately had no blocking: a phone kept working until its time ran
-- out. The owner asked for a way to stop a phone now (2026-11-15), so this adds
-- one, and nothing else changes:
--
--   * remove_access(device) ends the phone's time immediately. Its next
--     check-in (every 5 minutes, and whenever the app is opened) answers
--     "expired", and the app stops accepting and shows the lock screen.
--   * The owner can remove any phone. A reseller can remove only a phone that
--     redeemed one of their own codes.
--   * Undoing it is an ordinary Renew: the new code's months start from now.
--
-- Run once in the Supabase SQL editor. Safe to run again.

alter table public.device_events drop constraint if exists device_events_event_check;
alter table public.device_events add constraint device_events_event_check check (event in (
    'activated', 'renewed',
    'rejected_unknown', 'rejected_used', 'rejected_deleted',
    'rejected_other_phone', 'rate_limited',
    'removed'
));

create or replace function public.remove_access(p_device_id text)
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
    if not public.is_admin() then raise exception 'not allowed'; end if;

    if not public.is_owner() and not exists (
        select 1 from public.codes
         where redeemed_device = p_device_id and created_by = auth.uid()
    ) then
        raise exception 'not allowed';
    end if;

    update public.devices set expires_at = now() where device_id = p_device_id;
    if not found then raise exception 'no such phone'; end if;

    insert into public.device_events (device_id, event, by_admin)
    values (p_device_id, 'removed', auth.uid());
end;
$$;

revoke all on function public.remove_access(text) from public, anon;
grant execute on function public.remove_access(text) to authenticated;
