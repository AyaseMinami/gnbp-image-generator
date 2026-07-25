package io.github.ayaseminami.gnbp.ui.generation

import android.content.Context
import io.github.ayaseminami.gnbp.R
import io.github.ayaseminami.gnbp.generation.TaskCancellationReason
import io.github.ayaseminami.gnbp.generation.TaskFailureReason
import io.github.ayaseminami.gnbp.generation.TaskOutcomeUnknownReason
import io.github.ayaseminami.gnbp.generation.TaskStatus

internal fun Context.taskDiagnosticSummary(status: TaskStatus): String? = when (status) {
    is TaskStatus.Failed -> failureDiagnosticSummary(status)
    is TaskStatus.Cancelled -> getString(
        when (status.reason) {
            TaskCancellationReason.UserRequested -> R.string.task_cancelled_by_user
            TaskCancellationReason.ProcessInterruptedBeforeStart ->
                R.string.task_cancelled_process_interrupted
        },
    )
    is TaskStatus.OutcomeUnknown -> getString(
        when (status.reason) {
            TaskOutcomeUnknownReason.ProviderResponseUnknown -> R.string.task_unknown_provider
            TaskOutcomeUnknownReason.ProcessInterrupted -> R.string.task_unknown_process_interrupted
        },
    )
    TaskStatus.Queued,
    TaskStatus.Running,
    is TaskStatus.Succeeded,
    -> null
}

private fun Context.failureDiagnosticSummary(status: TaskStatus.Failed): String {
    if (status.reason == TaskFailureReason.HttpStatus) {
        val code = status.diagnostic?.httpStatusCode
        val message = status.diagnostic?.providerMessage
        return when {
            code != null && message != null -> getString(
                R.string.task_error_http_diagnostic,
                code,
                message,
            )
            code != null -> getString(R.string.task_error_http_code, code)
            message != null -> getString(R.string.task_error_provider_message, message)
            else -> getString(R.string.task_error_http_status)
        }
    }
    return getString(
        when (status.reason) {
            TaskFailureReason.ProviderUnavailable -> R.string.task_error_provider_unavailable
            TaskFailureReason.InvalidRequest -> R.string.task_error_invalid_request
            TaskFailureReason.Blocked -> R.string.task_error_blocked
            TaskFailureReason.HttpStatus -> error("HTTP failures are handled above")
            TaskFailureReason.Transport -> R.string.task_error_transport
            TaskFailureReason.MalformedResponse -> R.string.task_error_malformed_response
            TaskFailureReason.NoImageData -> R.string.task_error_no_image
            TaskFailureReason.ReferenceUnavailable -> R.string.task_error_reference_unavailable
            TaskFailureReason.AssetSaveFailed -> R.string.task_error_save_failed
        },
    )
}
