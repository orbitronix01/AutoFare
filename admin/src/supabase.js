import { createClient } from "@supabase/supabase-js";

// The same publishable key the app ships. It reaches nothing on its own: every
// table is RLS-locked, and the portal only sees rows once an admin signs in.
export const SUPABASE_URL = "https://mxugmesfstgdwjsknqlf.supabase.co";
const PUBLISHABLE_KEY = "sb_publishable_7IDCFHO-nqNXrWFlKfrK-Q_l8ERlc5e";

// `?demo` on the dev server swaps in sample data to preview the design. The
// import.meta.env.DEV guard is compiled away in a build, demo data and all.
export const supabase =
  import.meta.env.DEV && new URLSearchParams(location.search).has("demo")
    ? (await import("./demo.js")).demoClient
    : createClient(SUPABASE_URL, PUBLISHABLE_KEY);

// What a driver pays per month of subscription. Shown to the owner only.
export const PRICE_JD = 5;
export const priceOf = (months) => `${months * PRICE_JD} JD`;
// a forever code is for the owner's or a reseller's own phone: no sale
export const codePrice = (c) => (c.lifetime ? "Free" : priceOf(c.months));
export const codeLength = (c) => (c.lifetime ? "Forever" : `${c.months} ${c.months === 1 ? "month" : "months"}`);
/** A phone on a forever code runs until 2100. */
export const isForever = (expiresAt) => !!expiresAt && new Date(expiresAt).getUTCFullYear() >= 2099;

/** Turn a Supabase error into something worth showing, or throw-through the data. */
export function unwrap({ data, error }) {
  if (error) throw new Error(error.message);
  return data;
}

/** `ABCDEFGHJKMN` → `ABCD-EFGH-JKMN`: easier to read aloud and to type. */
export const prettyCode = (c) => (c ? c.match(/.{1,4}/g).join("-") : "");

export const fmtDate = (s) =>
  s ? new Date(s).toLocaleDateString("en-GB", { day: "numeric", month: "short", year: "numeric" }) : "—";

export const fmtDateTime = (s) =>
  s
    ? new Date(s).toLocaleString("en-GB", { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" })
    : "—";

export function daysLeft(s) {
  if (!s) return null;
  return Math.ceil((new Date(s) - Date.now()) / 86400000);
}
