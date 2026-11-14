-- AutoFare — one code per phone; time is controlled from the portal.
--
-- Until now a renewal was a new code the driver had to type. The owner asked
-- (2026-11-15) for one code per phone for life, with the time on it controlled
-- from the portal. So:
--
--   * A phone's code is the one it redeemed. The driver types it once.
--   * set_access(phone, …) changes that phone's time: add months, set an end
--     date, make it forever, or (remove_access, 0003) end it now. The phone
--     picks the change up at its next check-in — nothing to type.
--   * Typing the phone's own code again on the same phone (after a reinstall
--     or a data wipe) is accepted and adds nothing, instead of "used".
--   * Months added from the portal count as sold, like a code's months, and
--     appear as renewals on the overview.
--
-- Run once in the Supabase SQL editor after 0003. Safe to run again.

alter table public.codes add column if not exists extra_months int not null default 0;

alter table public.device_events drop constraint if exists device_events_event_check;
alter table public.device_events add constraint device_events_event_check check (event in (
    'activated', 'renewed',
    'rejected_unknown', 'rejected_used', 'rejected_deleted',
    'rejected_other_phone', 'rate_limited',
    'removed', 'extended', 'reentered'
));

-- ─────────────────────── redeem: the same code again is fine ───────────────────────

create or replace function public.redeem_code(p_code text, p_device_id text, p_app_version text default null)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_code   text := public.normalise_key(p_code);
    v_dev    text := btrim(coalesce(p_device_id, ''));
    c        public.codes%rowtype;
    v_fails  int;
    v_first  boolean;
    v_exp    timestamptz;
begin
    if length(v_code) = 0 or length(v_code) > 32 or length(v_dev) = 0 or length(v_dev) > 64 then
        return jsonb_build_object('ok', false, 'reason', 'bad_request');
    end if;

    select count(*) into v_fails from public.device_events
     where device_id = v_dev and at > now() - interval '1 hour'
       and event in ('rejected_unknown', 'rejected_used', 'rejected_deleted', 'rejected_other_phone', 'rate_limited');
    if v_fails >= 10 then
        insert into public.device_events (device_id, event) values (v_dev, 'rate_limited');
        return jsonb_build_object('ok', false, 'reason', 'rate_limited');
    end if;

    select * into c from public.codes where code = v_code for update;

    if not found then
        insert into public.device_events (device_id, event) values (v_dev, 'rejected_unknown');
        return jsonb_build_object('ok', false, 'reason', 'unknown_code');
    end if;
    if c.deleted then
        insert into public.device_events (device_id, code_id, event) values (v_dev, c.id, 'rejected_deleted');
        return jsonb_build_object('ok', false, 'reason', 'unknown_code');
    end if;

    -- this phone's own code, typed again: answer with the phone's time, add none
    if c.redeemed_at is not null and c.redeemed_device = v_dev then
        update public.devices set last_seen = now(), app_version = coalesce(p_app_version, app_version)
         where device_id = v_dev
        returning expires_at into v_exp;
        insert into public.device_events (device_id, code_id, event) values (v_dev, c.id, 'reentered');
        if v_exp is null or v_exp <= now() then
            return jsonb_build_object('ok', false, 'reason', 'expired', 'expires_at', v_exp, 'server_time', now());
        end if;
        return jsonb_build_object('ok', true, 'expires_at', v_exp, 'server_time', now(), 'first', false);
    end if;

    if c.redeemed_at is not null then
        insert into public.device_events (device_id, code_id, event) values (v_dev, c.id, 'rejected_used');
        return jsonb_build_object('ok', false, 'reason', 'other_phone');
    end if;
    if c.for_device is not null and c.for_device <> v_dev then
        insert into public.device_events (device_id, code_id, event) values (v_dev, c.id, 'rejected_other_phone');
        return jsonb_build_object('ok', false, 'reason', 'other_phone');
    end if;

    v_first := not exists (select 1 from public.codes where redeemed_device = v_dev);

    insert into public.devices (device_id, expires_at, last_seen, app_version)
    values (v_dev, case when c.lifetime then timestamptz '2100-01-01 00:00+00'
                        else now() + make_interval(months => c.months) end, now(), p_app_version)
    on conflict (device_id) do update
       set expires_at  = case when c.lifetime then greatest(timestamptz '2100-01-01 00:00+00', devices.expires_at)
                              else greatest(now(), coalesce(devices.expires_at, now())) + make_interval(months => c.months) end,
           last_seen   = now(),
           app_version = coalesce(excluded.app_version, devices.app_version)
    returning devices.expires_at into v_exp;

    update public.codes set redeemed_device = v_dev, redeemed_at = now() where id = c.id;

    -- one code per phone: the code just entered replaces any before it
    update public.codes set deleted = true, deleted_at = now()
     where (redeemed_device = v_dev or for_device = v_dev) and id <> c.id and not deleted;

    insert into public.device_events (device_id, code_id, event)
    values (v_dev, c.id, case when v_first then 'activated' else 'renewed' end);

    return jsonb_build_object('ok', true, 'expires_at', v_exp, 'server_time', now(), 'first', v_first);
end;
$$;

revoke all on function public.redeem_code(text, text, text) from public, anon, authenticated;
grant execute on function public.redeem_code(text, text, text) to anon, authenticated;

-- ─────────────────────── the portal sets a phone's time ───────────────────────

-- Exactly one of: p_months (add to what is left), p_until (end on that date),
-- p_forever (owner only). A reseller may change only a phone whose code they sold.
create or replace function public.set_access(p_device_id text, p_months int default null,
                                             p_until timestamptz default null, p_forever boolean default false)
returns timestamptz
language plpgsql
security definer
set search_path = public
as $$
declare
    cur   public.codes%rowtype;
    v_exp timestamptz;
begin
    if not public.is_admin() then raise exception 'not allowed'; end if;
    if (p_months is not null)::int + (p_until is not null)::int + coalesce(p_forever, false)::int <> 1 then
        raise exception 'choose one: months, an end date, or forever';
    end if;
    if p_months is not null and p_months not between 1 and 24 then raise exception 'months must be 1–24'; end if;
    if p_until is not null and p_until <= now() then raise exception 'the end date must be in the future'; end if;
    if p_forever and not public.is_owner() then raise exception 'only the owner can give forever'; end if;

    -- the phone's code: the newest it redeemed that is not deleted
    select * into cur from public.codes
     where redeemed_device = p_device_id and not deleted
     order by redeemed_at desc limit 1;

    if not public.is_owner() and (cur.id is null or cur.created_by is distinct from auth.uid()) then
        raise exception 'not allowed';
    end if;

    update public.devices
       set expires_at = case
               when p_forever then timestamptz '2100-01-01 00:00+00'
               when p_until is not null then p_until
               else greatest(now(), coalesce(expires_at, now())) + make_interval(months => p_months)
           end
     where device_id = p_device_id
    returning expires_at into v_exp;
    if not found then raise exception 'no such phone'; end if;

    -- months added are months sold, counted on the phone's code
    if p_months is not null and cur.id is not null then
        update public.codes set extra_months = extra_months + p_months where id = cur.id;
    end if;

    insert into public.device_events (device_id, code_id, event, by_admin)
    values (p_device_id, cur.id, 'extended', auth.uid());

    return v_exp;
end;
$$;

revoke all on function public.set_access(text, int, timestamptz, boolean) from public, anon;
grant execute on function public.set_access(text, int, timestamptz, boolean) to authenticated;

-- ─────────────────────── stats: count months added from the portal ───────────────────────

create or replace function public.admin_stats()
returns jsonb
language plpgsql
stable
security definer
set search_path = public
as $$
declare
    v_owner boolean := public.is_owner();
    v_out   jsonb;
begin
    if not public.is_admin() then raise exception 'not allowed'; end if;

    with my_codes as (
        select * from public.codes where v_owner or created_by = auth.uid()
    ),
    my_devices as (
        select d.* from public.devices d
         where v_owner or exists (select 1 from my_codes c where c.redeemed_device = d.device_id)
    ),
    my_events as (
        select e.* from public.device_events e
         where e.event in ('activated', 'renewed', 'extended')
           and (v_owner or e.code_id in (select id from my_codes))
    )
    select jsonb_build_object(
        'codes_created',    (select count(*) from my_codes),
        'codes_redeemed',   (select count(*) from my_codes where redeemed_at is not null),
        'codes_unused',     (select count(*) from my_codes where redeemed_at is null and not deleted),
        'codes_deleted',    (select count(*) from my_codes where deleted),
        'months_sold',      (select coalesce(sum(case when lifetime then 0 else months end + extra_months), 0)
                               from my_codes where redeemed_at is not null),
        'activations',      (select count(*) from my_events where event = 'activated'),
        'renewals',         (select count(*) from my_events where event in ('renewed', 'extended')),
        'devices_total',    (select count(*) from my_devices),
        'devices_active',   (select count(*) from my_devices where expires_at > now()),
        'devices_online',   (select count(*) from my_devices where last_seen > now() - interval '24 hours'),
        'by_month', (
            select coalesce(jsonb_agg(m order by m->>'month'), '[]'::jsonb) from (
                select jsonb_build_object(
                    'month',    to_char(mo, 'YYYY-MM'),
                    'created',  (select count(*) from my_codes where date_trunc('month', created_at) = mo),
                    'activated',(select count(*) from my_events where event = 'activated' and date_trunc('month', at) = mo),
                    'renewed',  (select count(*) from my_events where event in ('renewed', 'extended') and date_trunc('month', at) = mo)
                ) as m
                from generate_series(date_trunc('month', now()) - interval '5 months',
                                     date_trunc('month', now()), interval '1 month') mo
            ) months
        ),
        'by_reseller', (
            select coalesce(jsonb_agg(r order by r->>'name'), '[]'::jsonb) from (
                select jsonb_build_object(
                    'user_id',  a.user_id,
                    'name',     a.name,
                    'role',     a.role,
                    'active',   a.active,
                    'created',  (select count(*) from my_codes c where c.created_by = a.user_id),
                    'redeemed', (select count(*) from my_codes c where c.created_by = a.user_id and c.redeemed_at is not null),
                    'months',   (select coalesce(sum(case when c.lifetime then 0 else c.months end + c.extra_months), 0)
                                   from my_codes c where c.created_by = a.user_id and c.redeemed_at is not null)
                ) as r
                from public.admins a
                where v_owner or a.user_id = auth.uid()
            ) rs
        )
    ) into v_out;

    return v_out;
end;
$$;

revoke all on function public.admin_stats() from public, anon;
grant execute on function public.admin_stats() to authenticated;
