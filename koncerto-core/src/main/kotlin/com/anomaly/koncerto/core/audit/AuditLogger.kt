package com.anomaly.koncerto.core.audit

interface AuditLogger {
    fun log(event: AuditEvent)
}
