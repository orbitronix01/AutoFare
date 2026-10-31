import React, { useEffect, useState } from "react";
import { supabase } from "./supabase.js";
import { Icon, Avatar } from "./ui.jsx";
import Login from "./pages/Login.jsx";
import Overview from "./pages/Overview.jsx";
import Codes, { NewCodesModal } from "./pages/Codes.jsx";
import Devices from "./pages/Devices.jsx";
import Accounts from "./pages/Accounts.jsx";

const TABS = [
  { id: "overview", label: "Overview", page: Overview },
  { id: "codes", label: "Codes", page: Codes },
  { id: "devices", label: "Phones", page: Devices },
  { id: "accounts", label: "Team", page: Accounts, ownerOnly: true },
];

function useTheme() {
  const [theme, setTheme] = useState(() => {
    try {
      return localStorage.getItem("af-theme") || "dark";
    } catch {
      return "dark";
    }
  });
  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    try {
      localStorage.setItem("af-theme", theme);
    } catch {}
  }, [theme]);
  return [theme, () => setTheme((t) => (t === "dark" ? "light" : "dark"))];
}

export default function App() {
  const [theme, toggleTheme] = useTheme();
  const [session, setSession] = useState(undefined);
  const [me, setMe] = useState(null);
  const [problem, setProblem] = useState(null);
  const [tab, setTab] = useState(() => location.hash.slice(1) || "overview");
  const [making, setMaking] = useState(false);
  // bumped after anything that changes data, so the open page reloads
  const [version, setVersion] = useState(0);

  useEffect(() => {
    supabase.auth.getSession().then(({ data }) => setSession(data.session));
    const { data } = supabase.auth.onAuthStateChange((_e, s) => setSession(s));
    return () => data.subscription.unsubscribe();
  }, []);

  // signed in is not the same as allowed in: the admins row decides
  useEffect(() => {
    setMe(null);
    setProblem(null);
    if (!session) return;
    supabase
      .from("admins")
      .select("user_id, name, role, active")
      .eq("user_id", session.user.id)
      .maybeSingle()
      .then(({ data, error }) => {
        if (error) setProblem(error.message);
        else if (!data) setProblem("This login has no admin access, or it has been switched off.");
        else setMe(data);
      });
  }, [session]);

  useEffect(() => {
    if (location.hash.slice(1) !== tab) history.replaceState(null, "", `#${tab}`);
  }, [tab]);

  if (session === undefined) return <div className="center muted">Loading…</div>;
  if (!session) return <Login />;
  if (problem)
    return (
      <div className="center">
        <div className="panel login">
          <p>{problem}</p>
          <button className="btn ghost" onClick={() => supabase.auth.signOut()}>Sign out</button>
        </div>
      </div>
    );
  if (!me) return <div className="center muted">Loading…</div>;

  const isOwner = me.role === "owner";
  const tabs = TABS.filter((t) => !t.ownerOnly || isOwner);
  const current = tabs.find((t) => t.id === tab) ?? tabs[0];
  const Page = current.page;

  return (
    <>
      <header className="topbar">
        <div className="logo">
          <span className="logo-mark"><img src="/logo-mark.png" alt="" /></span>
          AutoFare
        </div>
        <nav className="pillnav">
          {tabs.map((t) => (
            <button key={t.id} className={t.id === current.id ? "on" : ""} onClick={() => setTab(t.id)}>
              {t.label}
            </button>
          ))}
        </nav>
        <div className="topright">
          <div className="me">
            <span>
              {me.name}
              <small>{me.role}</small>
            </span>
            <Avatar text={me.name} tone="lime" size={40} />
          </div>
        </div>
      </header>

      <div className="body">
        <aside className="rail">
          <button title="New codes" onClick={() => setMaking(true)}><Icon name="plus" /></button>
          <button title="Codes" onClick={() => setTab("codes")}><Icon name="ticket" /></button>
          <button title="Phones" onClick={() => setTab("devices")}><Icon name="phone" /></button>
          <button title="Refresh" onClick={() => setVersion((v) => v + 1)}><Icon name="refresh" /></button>
          <hr />
          <button title={theme === "dark" ? "Light mode" : "Dark mode"} onClick={toggleTheme}>
            <Icon name={theme === "dark" ? "sun" : "moon"} />
          </button>
          <button title="Sign out" onClick={() => supabase.auth.signOut()}><Icon name="logout" /></button>
        </aside>
        <main>
          <Page key={version} me={me} isOwner={isOwner} onChanged={() => setVersion((v) => v + 1)}
                onNewCodes={() => setMaking(true)} goTo={setTab} />
        </main>
      </div>

      {making && <NewCodesModal isOwner={isOwner} onClose={() => setMaking(false)} onMade={() => setVersion((v) => v + 1)} />}
    </>
  );
}
