package io.github.mschwehl.bb.secretvalidator;

import com.atlassian.bitbucket.scope.Scope;
import com.atlassian.bitbucket.setting.Settings;
import com.atlassian.bitbucket.setting.SettingsValidationErrors;
import com.atlassian.bitbucket.setting.SettingsValidator;
import javax.annotation.Nonnull;

public class SecretPluginSettingsValidator implements SettingsValidator {

    @Override
    public void validate(@Nonnull Settings settings, @Nonnull SettingsValidationErrors errors, @Nonnull Scope scope) {
        if (settings.getString("mergeCommitMsgRegex").trim().isEmpty()) {
            errors.addFieldError("mergeCommitMsgRegex", "A glob pattern is required");
        }

        if (settings.getString("mergeCommitMsgRegex").trim().indexOf("*") < 0) {
            errors.addFieldError("mergeCommitMsgRegex", "Glob pattern must contain '*'");
        }

        if (settings.getString("mergeCommitMsgSummaryText").trim().isEmpty()) {
            errors.addFieldError("mergeCommitMsgSummaryText", "A summary notification message is required");
        }

        if (settings.getString("mergeCommitMsgDetailedText").trim().isEmpty()) {
            errors.addFieldError("mergeCommitMsgDetailedText", "A detailed notification message is required");
        }
    }
}
