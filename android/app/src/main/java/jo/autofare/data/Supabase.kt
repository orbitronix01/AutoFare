package jo.autofare.data

/**
 * Where the licence server is.
 *
 * The publishable key is in the APK, and that is fine — it is designed to be.
 * Anyone can pull it out with `apktool`, which is exactly why `licenses` and
 * `activations` have RLS on with no policies: this key cannot read either table.
 * The only two things it can reach are `activate_license` and `check_license`,
 * each of which answers one narrow question about one key.
 *
 * The service-role key must never appear in this file or anywhere else in the
 * app. It bypasses every policy in the project.
 *
 * Schema lives in `supabase/migrations/0001_licensing.sql`.
 */
object Supabase {
    const val URL = "https://mxugmesfstgdwjsknqlf.supabase.co"
    const val PUBLISHABLE_KEY = "sb_publishable_7IDCFHO-nqNXrWFlKfrK-Q_l8ERlc5e"
}
