package app.aaps.core.interfaces.notifications

enum class NotificationCategory {
    PUMP,
    PROFILE,
    CGM,
    LOOP,
    SYNC,
    SYSTEM,
    AUTOMATION,
    // 05/07/2026 (Ecko): eigen categorie voor FCLvNext-meldingen (AI Advisor),
    // zodat deze een eigen, herkenbaar icoon krijgt i.p.v. het gedeelde
    // AUTOMATION-icoon (dat hoort bij de losstaande Automation-plugin).
    FCL,
    GENERAL
}
