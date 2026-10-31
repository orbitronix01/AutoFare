// AutoFare — portal account management.
//
// Making a login needs the service-role key, which must never reach a browser.
// So the portal calls this function with the signed-in owner's JWT, and the
// function checks that caller against public.admins before doing anything.
//
//   POST { action: "create",       email, password, name }
//   POST { action: "set_password", user_id, password }

import { createClient } from "npm:@supabase/supabase-js@2.45.4";

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

function reply(status: number, body: unknown) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...cors, "Content-Type": "application/json" },
  });
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "POST") return reply(405, { error: "POST only" });

  const url = Deno.env.get("SUPABASE_URL")!;
  const service = createClient(url, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, {
    auth: { persistSession: false },
  });

  // who is asking
  const jwt = (req.headers.get("Authorization") ?? "").replace(/^Bearer\s+/i, "");
  const { data: who } = await service.auth.getUser(jwt);
  if (!who?.user) return reply(401, { error: "sign in first" });

  const { data: me } = await service
    .from("admins")
    .select("role, active")
    .eq("user_id", who.user.id)
    .maybeSingle();
  if (!me || !me.active || me.role !== "owner") return reply(403, { error: "owner only" });

  let body: Record<string, string>;
  try {
    body = await req.json();
  } catch {
    return reply(400, { error: "bad json" });
  }

  const password = String(body.password ?? "");
  if (password.length < 10) return reply(400, { error: "password must be at least 10 characters" });

  if (body.action === "create") {
    const email = String(body.email ?? "").trim().toLowerCase();
    const name = String(body.name ?? "").trim();
    if (!email.includes("@") || !name) return reply(400, { error: "email and name are required" });

    const { data: made, error } = await service.auth.admin.createUser({
      email,
      password,
      email_confirm: true,
    });
    if (error || !made.user) return reply(400, { error: error?.message ?? "could not create user" });

    const { error: rowErr } = await service
      .from("admins")
      .insert({ user_id: made.user.id, name, role: "reseller" });
    if (rowErr) {
      // never leave a login behind that has no admins row
      await service.auth.admin.deleteUser(made.user.id);
      return reply(400, { error: rowErr.message });
    }
    return reply(200, { user_id: made.user.id });
  }

  if (body.action === "set_password") {
    const userId = String(body.user_id ?? "");
    const { data: target } = await service.from("admins").select("user_id").eq("user_id", userId).maybeSingle();
    if (!target) return reply(404, { error: "no such account" });

    const { error } = await service.auth.admin.updateUserById(userId, { password });
    if (error) return reply(400, { error: error.message });
    return reply(200, { ok: true });
  }

  return reply(400, { error: "unknown action" });
});
