-- AutoFare — one-time codes, devices, and the admin portal.
--
-- Replaces the 0001 model (one key, renewed in place) for the app, but leaves
-- 0001's tables and functions exactly where they are: there is data in them
-- nobody has identified yet, and nothing here reads or writes it.
--
-- The model:
--   * A CODE is a one-time voucher worth N months. It is made by an admin,
--     handed to a driver, and dies the moment it is redeemed.
--   * A DEVICE is the phone. Its subscription is a single expiry. Redeeming a
--     code pushes that expiry out by the code's months, from the later of now
--     and the current expiry — paying early never costs the driver days.
--   * An ADMIN is a portal login: one owner, any number of resellers. A
--     reseller sees and makes only their own codes; the owner sees everything
--     and makes resellers.
--   * There is no blocking: a phone loses access when its time runs out.
--   * A driver holds one code at a time. Renewing a phone makes a code that
--     only that phone can redeem and deletes the phone's older codes; any code
--     redeemed on a phone deletes the ones it redeemed before.
--   * The owner can make FOREVER codes (for the owner's and resellers' own
--     phones): they push the phone's expiry to 2100 and count as no sale.
--
-- Same lockdown rule as 0001: the app's publishable key reaches exactly two
-- functions, redeem_code() and device_status(), and no table at all.

create extension if not exists pgcrypto with schema extensions;

-- ─────────────────────────── tables ───────────────────────────

create table if not exists public.admins (
    user_id     uuid primary key references auth.users(id) on delete cascade,
    name        text not null,
    role        text not null default 'reseller' check (role in ('owner', 'reseller')),
    active      boolean not null default true,
    created_at  timestamptz not null default now()
);

create table if not exists public.devices (
    -- ANDROID_ID: per signing key, survives updates, resets on factory reset
    device_id    text primary key,
    expires_at   timestamptz,
    first_seen   timestamptz not null default now(),
    -- touched by every device_status() call; "online in the last day" is read off this
    last_seen    timestamptz,
    app_version  text
);

create table if not exists public.codes (
    id               uuid primary key default gen_random_uuid(),
    code             text unique not null,
    months           smallint not null check (months between 1 and 12),
    -- a forever code: months is ignored, the phone runs until 2100
    lifetime         boolean not null default false,
    created_by       uuid references public.admins(user_id) on delete set null,
    -- who it was sold to — a name or a phone number, free text
    customer         text,
    created_at       timestamptz not null default now(),
    redeemed_device  text references public.devices(device_id) on delete set null,
    redeemed_at      timestamptz,
    -- set on a renewal: only this phone can redeem the code
    for_device       text references public.devices(device_id) on delete set null,
    -- deleted codes are hidden from the portal and cannot be redeemed, but
    -- stay in the stats: the months they sold were still sold
    deleted          boolean not null default false,
    deleted_at       timestamptz
);

-- Every redemption and every refusal. The refusals are the only place
-- a brute-force attempt against the code space becomes visible.
create table if not exists public.device_events (
    id         bigserial primary key,
    device_id  text not null,
    code_id    uuid references public.codes(id) on delete set null,
    event      text not null check (event in (
                   'activated', 'renewed',
                   'rejected_unknown', 'rejected_used', 'rejected_deleted',
                   'rejected_other_phone', 'rate_limited'
               )),
    by_admin   uuid references public.admins(user_id) on delete set null,
    at         timestamptz not null default now()
);

create index if not exists codes_created_by_idx     on public.codes (created_by, created_at desc);
create index if not exists codes_redeemed_dev_idx   on public.codes (redeemed_device);
create index if not exists codes_for_dev_idx        on public.codes (for_device);
create index if not exists device_events_dev_idx    on public.device_events (device_id, at desc);
create index if not exists device_events_at_idx     on public.device_events (at desc);

-- ─────────────────────────── lockdown ───────────────────────────

alter table public.admins        enable row level security;
alter table public.devices       enable row level security;
alter table public.codes         enable row level security;
alter table public.device_events enable row level security;

revoke all on public.admins, public.devices, public.codes, public.device_events from anon, authenticated;
revoke all on sequence public.device_events_id_seq from anon, authenticated;

-- ─────────────────────────── helpers ───────────────────────────

-- security definer so policies can call them without recursing through RLS
create or replace function public.is_admin()
returns boolean language sql stable security definer set search_path = public as $$
    select exists (select 1 from public.admins where user_id = auth.uid() and active);
$$;

create or replace function public.is_owner()
returns boolean language sql stable security definer set search_path = public as $$
    select exists (select 1 from public.admins where user_id = auth.uid() and active and role = 'owner');
$$;

/*
 * A new code: 12 characters, no 0/O/1/I/L, from the CSPRNG.
 *
 * 0001's generator uses random(), which is not a cryptographic source. A code
 * is money, so this one draws from gen_random_bytes and rejects bytes past the
 * last whole multiple of 31 to keep the alphabet unbiased.
 */
create or replace function public.new_code()
returns text language plpgsql volatile set search_path = public, extensions as $$
declare
    alphabet constant text := 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';
    v_out text := '';
    b int;
begin
    while length(v_out) < 12 loop
        b := get_byte(gen_random_bytes(1), 0);
        if b < 248 then  -- 248 = 31 * 8
            v_out := v_out || substr(alphabet, 1 + (b % 31), 1);
        end if;
    end loop;
    return v_out;
end;
$$;

revoke all on function public.is_admin(), public.is_owner(), public.new_code() from public, anon;
grant execute on function public.is_admin(), public.is_owner() to authenticated;

-- ─────────────────────────── read access for the portal ───────────────────────────

grant select on public.admins, public.devices, public.codes, public.device_events to authenticated;

drop policy if exists admins_read on public.admins;
create policy admins_read on public.admins for select to authenticated
    using (public.is_admin() and (user_id = auth.uid() or public.is_owner()));

drop policy if exists codes_read on public.codes;
create policy codes_read on public.codes for select to authenticated
    using (public.is_admin() and not deleted and (created_by = auth.uid() or public.is_owner()));

-- a reseller sees the phones that hold one of their codes, nothing else
drop policy if exists devices_read on public.devices;
create policy devices_read on public.devices for select to authenticated
    using (public.is_admin() and (public.is_owner() or exists (
        select 1 from public.codes c
         where (c.redeemed_device = devices.device_id or c.for_device = devices.device_id)
           and c.created_by = auth.uid())));

drop policy if exists device_events_read on public.device_events;
create policy device_events_read on public.device_events for select to authenticated
    using (public.is_admin() and (public.is_owner() or exists (
        select 1 from public.codes c
         where c.id = device_events.code_id and c.created_by = auth.uid())));

-- ─────────────────────── the app's only entry points ───────────────────────

/*
 * Redeem a code on this phone.
 *
 * Never raises — the app has to tell "wrong code" apart from "no network".
 *
 *   { "ok": true,  "expires_at": "...", "server_time": "...", "first": true }
 *   { "ok": false, "reason": "unknown_code" | "used" | "other_phone" | "rate_limited" | "bad_request" }
 *
 * A deleted code answers unknown_code: to the driver it no longer exists.
 */
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

    -- ten wrong guesses an hour is generous for a driver mistyping off WhatsApp
    -- and useless to a script walking the code space
    select count(*) into v_fails from public.device_events
     where device_id = v_dev and at > now() - interval '1 hour'
       and event in ('rejected_unknown', 'rejected_used', 'rejected_deleted', 'rejected_other_phone', 'rate_limited');
    if v_fails >= 10 then
        insert into public.device_events (device_id, event) values (v_dev, 'rate_limited');
        return jsonb_build_object('ok', false, 'reason', 'rate_limited');
    end if;

    -- FOR UPDATE: two phones redeeming one code at the same instant must not both win
    select * into c from public.codes where code = v_code for update;

    if not found then
        insert into public.device_events (device_id, event) values (v_dev, 'rejected_unknown');
        return jsonb_build_object('ok', false, 'reason', 'unknown_code');
    end if;
    if c.deleted then
        insert into public.device_events (device_id, code_id, event) values (v_dev, c.id, 'rejected_deleted');
        return jsonb_build_object('ok', false, 'reason', 'unknown_code');
    end if;
    if c.redeemed_at is not null then
        insert into public.device_events (device_id, code_id, event) values (v_dev, c.id, 'rejected_used');
        return jsonb_build_object('ok', false, 'reason', 'used');
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

    -- one code per driver: the code just entered replaces the ones before it
    update public.codes set deleted = true, deleted_at = now()
     where redeemed_device = v_dev and id <> c.id and not deleted;

    insert into public.device_events (device_id, code_id, event)
    values (v_dev, c.id, case when v_first then 'activated' else 'renewed' end);

    return jsonb_build_object('ok', true, 'expires_at', v_exp, 'server_time', now(), 'first', v_first);
end;
$$;

/*
 * Where does this phone stand. Called every few minutes by the app.
 *
 * Always carries server_time, so the app measures the subscription against the
 * server's clock rather than a phone clock the driver can wind back.
 *
 *   { "ok": true,  "expires_at": "...", "server_time": "..." }
 *   { "ok": false, "reason": "none" | "expired" | "bad_request", "server_time": "..." }
 */
create or replace function public.device_status(p_device_id text, p_app_version text default null)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_dev text := btrim(coalesce(p_device_id, ''));
    d     public.devices%rowtype;
begin
    if length(v_dev) = 0 or length(v_dev) > 64 then
        return jsonb_build_object('ok', false, 'reason', 'bad_request', 'server_time', now());
    end if;

    -- only phones that have redeemed something have a row; an unknown id is
    -- answered without writing anything, so random ids cannot fill the table
    update public.devices
       set last_seen = now(), app_version = coalesce(p_app_version, app_version)
     where device_id = v_dev
    returning * into d;

    if not found then
        return jsonb_build_object('ok', false, 'reason', 'none', 'server_time', now());
    end if;
    if d.expires_at is null or d.expires_at <= now() then
        return jsonb_build_object('ok', false, 'reason', 'expired', 'expires_at', d.expires_at, 'server_time', now());
    end if;

    return jsonb_build_object('ok', true, 'expires_at', d.expires_at, 'server_time', now());
end;
$$;

revoke all on function public.redeem_code(text, text, text)  from public, anon, authenticated;
revoke all on function public.device_status(text, text)      from public, anon, authenticated;
grant execute on function public.redeem_code(text, text, text) to anon, authenticated;
grant execute on function public.device_status(text, text)     to anon, authenticated;

-- ─────────────────────────── portal actions ───────────────────────────

-- Make 1–50 codes worth p_months each, owned by whoever is signed in.
--
-- With p_device it is a renewal: one code, redeemable only on that phone, and
-- the phone's older codes are deleted so the driver holds just this one. The
-- time already on the phone is untouched until the new code is entered.
create or replace function public.create_codes(p_months int, p_count int default 1, p_customer text default null,
                                               p_device text default null, p_lifetime boolean default false)
returns setof public.codes
language plpgsql
security definer
set search_path = public
as $$
declare
    i int;
    c public.codes%rowtype;
begin
    if not public.is_admin() then raise exception 'not allowed'; end if;
    if p_months not between 1 and 12 then raise exception 'months must be 1–12'; end if;
    if p_count  not between 1 and 50 then raise exception 'count must be 1–50'; end if;
    if p_lifetime and not public.is_owner() then raise exception 'only the owner can make forever codes'; end if;

    if p_device is not null then
        if p_count <> 1 then raise exception 'a renewal is one code'; end if;
        perform 1 from public.devices where device_id = p_device for update;
        if not found then raise exception 'no such phone'; end if;
        -- a reseller renews only drivers who hold one of their codes
        if not public.is_owner() and not exists (
            select 1 from public.codes
             where (redeemed_device = p_device or for_device = p_device)
               and created_by = auth.uid() and not deleted) then
            raise exception 'not allowed';
        end if;
        update public.codes set deleted = true, deleted_at = now()
         where (redeemed_device = p_device or for_device = p_device) and not deleted;
    end if;

    for i in 1..p_count loop
        loop
            begin
                insert into public.codes (code, months, created_by, customer, for_device, lifetime)
                values (public.new_code(), p_months, auth.uid(), nullif(btrim(p_customer), ''), p_device, p_lifetime)
                returning * into c;
                return next c;
                exit;
            exception when unique_violation then
                -- 31^12 makes this all but impossible; draw again if it happens
            end;
        end loop;
    end loop;
end;
$$;

-- Delete a code, used or not. An unused one can then never be redeemed; a used
-- one has already become time on a phone, and that time is left alone.
create or replace function public.delete_code(p_id uuid)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
    c public.codes%rowtype;
begin
    if not public.is_admin() then raise exception 'not allowed'; end if;
    select * into c from public.codes where id = p_id for update;
    if not found then raise exception 'no such code'; end if;
    if c.created_by is distinct from auth.uid() and not public.is_owner() then raise exception 'not allowed'; end if;
    update public.codes set deleted = true, deleted_at = now() where id = p_id;
end;
$$;

-- Owner only. Switching a reseller off stops them signing in to anything useful;
-- codes they already sold keep working.
create or replace function public.set_admin_active(p_user_id uuid, p_active boolean)
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
    if not public.is_owner() then raise exception 'not allowed'; end if;
    if p_user_id = auth.uid() then raise exception 'you cannot switch yourself off'; end if;
    update public.admins set active = p_active where user_id = p_user_id;
    if not found then raise exception 'no such account'; end if;
end;
$$;

/*
 * The numbers on the dashboard. The owner gets everything plus a per-reseller
 * breakdown; a reseller gets the same figures scoped to their own codes.
 */
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
         where e.event in ('activated', 'renewed')
           and (v_owner or e.code_id in (select id from my_codes))
    )
    select jsonb_build_object(
        'codes_created',    (select count(*) from my_codes),
        'codes_redeemed',   (select count(*) from my_codes where redeemed_at is not null),
        'codes_unused',     (select count(*) from my_codes where redeemed_at is null and not deleted),
        'codes_deleted',    (select count(*) from my_codes where deleted),
        'months_sold',      (select coalesce(sum(months), 0) from my_codes where redeemed_at is not null and not lifetime),
        'activations',      (select count(*) from my_events where event = 'activated'),
        'renewals',         (select count(*) from my_events where event = 'renewed'),
        'devices_total',    (select count(*) from my_devices),
        'devices_active',   (select count(*) from my_devices where expires_at > now()),
        'devices_online',   (select count(*) from my_devices where last_seen > now() - interval '24 hours'),
        'by_month', (
            select coalesce(jsonb_agg(m order by m->>'month'), '[]'::jsonb) from (
                select jsonb_build_object(
                    'month',    to_char(mo, 'YYYY-MM'),
                    'created',  (select count(*) from my_codes where date_trunc('month', created_at) = mo),
                    'activated',(select count(*) from my_events where event = 'activated' and date_trunc('month', at) = mo),
                    'renewed',  (select count(*) from my_events where event = 'renewed'   and date_trunc('month', at) = mo)
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
                    'months',   (select coalesce(sum(c.months), 0) from my_codes c where c.created_by = a.user_id and c.redeemed_at is not null and not c.lifetime)
                ) as r
                from public.admins a
                where v_owner or a.user_id = auth.uid()
            ) rs
        )
    ) into v_out;

    return v_out;
end;
$$;

revoke all on function public.create_codes(int, int, text, text, boolean)             from public, anon;
revoke all on function public.delete_code(uuid)                           from public, anon;
revoke all on function public.set_admin_active(uuid, boolean)             from public, anon;
revoke all on function public.admin_stats()                               from public, anon;
grant execute on function public.create_codes(int, int, text, text, boolean)           to authenticated;
grant execute on function public.delete_code(uuid)                        to authenticated;
grant execute on function public.set_admin_active(uuid, boolean)          to authenticated;
grant execute on function public.admin_stats()                            to authenticated;
