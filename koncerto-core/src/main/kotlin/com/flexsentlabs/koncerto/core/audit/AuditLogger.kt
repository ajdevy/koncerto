package com.flexsentlabs.koncerto.core.audit

interface AuditLogger {
    fun log(event: AuditEvent)
}
