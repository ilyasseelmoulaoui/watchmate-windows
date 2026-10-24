package app.agregateur

/** Même projet Supabase que l'app Android ; la clé anon est publique (RLS côté serveur). */
object BuildConfig {
    const val SUPABASE_URL: String =
        "https://uwhiyowjphecarstlxxp.supabase.co"
    const val SUPABASE_ANON_KEY: String =
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InV3aGl5b3dqcGhlY2Fyc3RseHhwIiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTI0MzUxNTIsImV4cCI6MjEwODAxMTE1Mn0.QFTc_PfQ5Qr2QdN0S8gHgPgyOhlITCf6hx-rkrQUvAA"

    /** Serveur TURN optionnel pour le vocal (vide = STUN public seulement). */
    const val TURN_URL: String = ""
    const val TURN_USERNAME: String = ""
    const val TURN_CREDENTIAL: String = ""
}
