import React, { useState } from "react";
import { supabase } from "../supabase.js";

export default function Login() {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  async function submit(e) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    const { error } = await supabase.auth.signInWithPassword({ email: email.trim(), password });
    if (error) setError(error.message);
    setBusy(false);
  }

  return (
    <div className="center">
      <div className="login">
        <div className="logo">
          <span className="logo-mark"><img src="/logo-mark.png" alt="" /></span>
          AutoFare
        </div>
        <form className="panel" onSubmit={submit}>
          <div>
            <h3>Sign in</h3>
            <span className="muted">Admin portal for codes and subscriptions</span>
          </div>
          <label className="field">
            Email
            <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} autoFocus required />
          </label>
          <label className="field">
            Password
            <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} required />
          </label>
          {error && <p className="error">{error}</p>}
          <button className="btn lime" disabled={busy}>{busy ? "Signing in…" : "Sign in"}</button>
        </form>
      </div>
    </div>
  );
}
