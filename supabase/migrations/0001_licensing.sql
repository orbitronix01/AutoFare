-- AutoFare — licensing schema.
--
-- Design rule: the Android app ships a PUBLISHABLE key, which anyone can read
-- out of the APK. So the app is never allowed to touch these tables directly.
-- RLS is on with no policies at all, which denies everything, and the only way
-- in is through two SECURITY DEFINER functions that take a key and a device id
-- and answer one narrow question each. A stolen publishable key therefore buys
-- an attacker nothing except the ability to guess licence keys one at a time.
--
-- Run this in the Supabase SQL editor (Dashboard -> SQL -> New query).

-- ─────────────────────────── tables ───────────────────────────

create table if not exists public.licenses (
    id            uuid primary key default gen_random_uuid(),

    -- what the driver types in. Stored upper-case; see normalise_key().
    key           text unique not null,

    -- who paid, so a CliQ/Zain Cash receipt can be matched to a licence by hand
    phone         text,

    -- bound on first activation. Without this one key gets forwarded around a
    -- WhatsApp group and fifty drivers run on a single 5 JOD subscription.
    device_id     text,

    activated_at  timestamptz,
    expires_at    timestamptz not null,

    -- genuine phone changes happen; serial sharing does not. Two rebinds is
    -- enough for an honest driver and useless to someone reselling the key.
    rebinds_used  smallint not null default 0,

    revoked       boolean not null default false,
    note          text,
    created_at    timestamptz not null default now()
);

comment on table public.licenses is
    'One row per paid subscription. Never readable by the app — see activate_license().';

-- Every attempt, successful or not. This is the only place a brute-force
-- attempt against the key space becomes visible, so do not drop it.
create table if not exists public.activations (
    id          bigserial primary key,
    license_id  uuid references public.licenses(id) on delete set null,
    device_id   text not null,
    result      text not null check (result in (
                    'activated', 'rebound', 'renewed',
                    'rejected_unknown', 'rejected_device',
                    'rejected_expired', 'rejected_revoked'
                )),
    at          timestamptz not null default now()
);

create index if not exists activations_at_idx      on public.activations (at desc);
create index if not exists activations_device_idx  on public.activations (device_id, at desc);
create index if not exists licenses_expires_idx    on public.licenses (expires_at);

-- ─────────────────────────── lockdown ───────────────────────────

alter table public.licenses    enable row level security;
alter table public.activations enable row level security;

-- Deliberately NO policies. With RLS on and nothing granted, anon and
-- authenticated can read and write nothing. The service role still has full
-- access for your own admin work in the dashboard.
revoke all on public.licenses    from anon, authenticated;
revoke all on public.activations from anon, authenticated;

-- ─────────────────────────── helpers ───────────────────────────

-- Drivers will type the key with spaces, lower case, or Arabic-Indic digits
-- pasted out of WhatsApp. Normalise before comparing or nothing ever matches.
create or replace function public.normalise_key(p_key text)
returns text
language sql
immutable
as $$
    select upper(regexp_replace(coalesce(p_key, ''), '[^A-Za-z0-9]', '', 'g'));
$$;

-- Key generator. Ambiguous characters (0/O, 1/I/L) are left out so a key read
-- aloud over the phone or copied off a screenshot cannot be mistyped.
create or replace function public.generate_license_key()
returns text
language plpgsql
volatile
as $$
declare
    alphabet constant text := 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';
    v_out text := '';
    i int;
begin
    for i in 1..12 loop
        v_out := v_out || substr(alphabet, 1 + floor(random() * length(alphabet))::int, 1);
    end loop;
    return v_out;
end;
$$;

-- ─────────────────────── the app's only entry points ───────────────────────

/*
 * Activate (or re-check) a licence on a device.
 *
 * Returns, always as jsonb, never raising — the app must be able to tell
 * "your key is wrong" apart from "the network is down", and an exception
 * across PostgREST blurs that line.
 *
 *   { "ok": true,  "expires_at": "...", "days_left": 27, "rebinds_left": 2 }
 *   { "ok": false, "reason": "unknown_key" | "expired" | "revoked"
 *                            | "device_limit" | "bad_request" }
 */
create or replace function public.activate_license(p_key text, p_device_id text)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    l       public.licenses%rowtype;
    v_key   text := public.normalise_key(p_key);
    v_dev   text := btrim(coalesce(p_device_id, ''));
    v_what  text;
begin
    if length(v_key) = 0 or length(v_dev) = 0 then
        return jsonb_build_object('ok', false, 'reason', 'bad_request');
    end if;

    -- FOR UPDATE: two phones activating the same key at the same instant must
    -- not both see device_id as null and both bind.
    select * into l from public.licenses where key = v_key for update;

    if not found then
        insert into public.activations (license_id, device_id, result)
        values (null, v_dev, 'rejected_unknown');
        return jsonb_build_object('ok', false, 'reason', 'unknown_key');
    end if;

    if l.revoked then
        insert into public.activations (license_id, device_id, result)
        values (l.id, v_dev, 'rejected_revoked');
        return jsonb_build_object('ok', false, 'reason', 'revoked');
    end if;

    if l.expires_at <= now() then
        insert into public.activations (license_id, device_id, result)
        values (l.id, v_dev, 'rejected_expired');
        return jsonb_build_object('ok', false, 'reason', 'expired');
    end if;

    if l.device_id is null then
        update public.licenses
           set device_id    = v_dev,
               activated_at = coalesce(activated_at, now())
         where id = l.id;
        v_what := 'activated';

    elsif l.device_id = v_dev then
        -- same phone checking in again; idempotent on purpose
        v_what := 'activated';

    elsif l.rebinds_used < 2 then
        update public.licenses
           set device_id    = v_dev,
               rebinds_used = rebinds_used + 1
         where id = l.id
        returning licenses.rebinds_used into l.rebinds_used;
        v_what := 'rebound';

    else
        insert into public.activations (license_id, device_id, result)
        values (l.id, v_dev, 'rejected_device');
        return jsonb_build_object('ok', false, 'reason', 'device_limit');
    end if;

    insert into public.activations (license_id, device_id, result)
    values (l.id, v_dev, v_what);

    return jsonb_build_object(
        'ok',           true,
        'expires_at',   l.expires_at,
        'days_left',    greatest(0, ceil(extract(epoch from (l.expires_at - now())) / 86400))::int,
        'rebinds_left', greatest(0, 2 - l.rebinds_used)
    );
end;
$$;

/*
 * Periodic re-check. Same answers, but it will NOT bind or rebind a device —
 * a background check must never silently consume a rebind.
 */
create or replace function public.check_license(p_key text, p_device_id text)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    l     public.licenses%rowtype;
    v_key text := public.normalise_key(p_key);
    v_dev text := btrim(coalesce(p_device_id, ''));
begin
    if length(v_key) = 0 or length(v_dev) = 0 then
        return jsonb_build_object('ok', false, 'reason', 'bad_request');
    end if;

    select * into l from public.licenses where key = v_key;

    if not found then return jsonb_build_object('ok', false, 'reason', 'unknown_key'); end if;
    if l.revoked  then return jsonb_build_object('ok', false, 'reason', 'revoked');     end if;
    if l.device_id is distinct from v_dev then
        return jsonb_build_object('ok', false, 'reason', 'device_limit');
    end if;
    if l.expires_at <= now() then return jsonb_build_object('ok', false, 'reason', 'expired'); end if;

    return jsonb_build_object(
        'ok',         true,
        'expires_at', l.expires_at,
        'days_left',  greatest(0, ceil(extract(epoch from (l.expires_at - now())) / 86400))::int
    );
end;
$$;

-- Only these two functions are reachable with the publishable key.
revoke all on function public.activate_license(text, text) from public, anon, authenticated;
revoke all on function public.check_license(text, text)    from public, anon, authenticated;
revoke all on function public.generate_license_key()       from public, anon, authenticated;
revoke all on function public.normalise_key(text)          from public, anon, authenticated;

grant execute on function public.activate_license(text, text) to anon;
grant execute on function public.check_license(text, text)    to anon;

-- ─────────────────────────── admin ───────────────────────────

/*
 * Issue or extend a licence. Service role only — call it from the SQL editor
 * after a CliQ or Zain Cash receipt lands on WhatsApp.
 *
 *   select * from public.issue_license('0779xxxxxxx', 1);     -- new, 1 month
 *   select * from public.issue_license('0779xxxxxxx', 1, 'AFXXXXXXXXXXXX');  -- renew
 *
 * Renewal extends from the later of now() and the current expiry, so paying
 * early never costs the driver days.
 */
create or replace function public.issue_license(
    p_phone  text,
    p_months int  default 1,
    p_key    text default null
)
returns table (license_key text, valid_until timestamptz)
language plpgsql
security definer
set search_path = public
as $$
declare
    v_key  text := public.normalise_key(p_key);
    l      public.licenses%rowtype;
    -- locals rather than writing straight into the OUT columns: `key` and
    -- `expires_at` as output variables would shadow the table's own columns
    -- and every reference to them becomes ambiguous at runtime.
    v_outk text;
    v_oute timestamptz;
begin
    if p_months < 1 then
        raise exception 'p_months must be at least 1';
    end if;

    if length(v_key) > 0 then
        select * into l from public.licenses where licenses.key = v_key for update;
        if not found then
            raise exception 'no licence with key %', v_key;
        end if;

        update public.licenses
           set expires_at = greatest(now(), l.expires_at) + make_interval(months => p_months),
               phone      = coalesce(p_phone, l.phone),
               revoked    = false
         where id = l.id
        returning licenses.key, licenses.expires_at into v_outk, v_oute;

        insert into public.activations (license_id, device_id, result)
        values (l.id, coalesce(l.device_id, 'admin'), 'renewed');

        return query select v_outk, v_oute;
        return;
    end if;

    -- new licence; retry on the vanishingly unlikely key collision
    loop
        begin
            insert into public.licenses (key, phone, expires_at)
            values (public.generate_license_key(), p_phone,
                    now() + make_interval(months => p_months))
            returning licenses.key, licenses.expires_at into v_outk, v_oute;

            return query select v_outk, v_oute;
            return;
        exception when unique_violation then
            -- try again with a fresh key
        end;
    end loop;
end;
$$;

revoke all on function public.issue_license(text, int, text) from public, anon, authenticated;

-- Everything issued, newest first — your day-to-day admin view.
create or replace view public.license_overview as
select
    l.key,
    l.phone,
    l.expires_at,
    greatest(0, ceil(extract(epoch from (l.expires_at - now())) / 86400))::int as days_left,
    (l.expires_at <= now())          as expired,
    l.revoked,
    (l.device_id is not null)        as bound,
    l.rebinds_used,
    l.activated_at,
    l.note
from public.licenses l
order by l.expires_at desc;

revoke all on public.license_overview from anon, authenticated;
