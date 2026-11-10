/*  Copyright (C) 2026 Gadgetbridge contributors

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.activities.selfhostedhealth;

import android.os.Bundle;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.core.view.MenuProvider;

import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.AbstractGBActivity;
import nodomain.freeyourgadget.gadgetbridge.util.selfhostedhealth.SelfHostedHealthLogEntry;

/**
 * Full-screen detail for one sync-log entry, showing everything the row omits: the exact endpoint,
 * timestamp, response time, record count and a bounded failure category. Health values and
 * request bodies are never retained.
 */
public class SelfHostedHealthLogDetailActivity extends AbstractGBActivity implements MenuProvider {
    private static final Logger LOG = LoggerFactory.getLogger(SelfHostedHealthLogDetailActivity.class);

    /** The entry serialized with {@link SelfHostedHealthLogEntry#toJson()}. */
    public static final String EXTRA_ENTRY = "entry";

    private final SimpleDateFormat fullFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_selfhosted_health_log_detail);
        addMenuProvider(this);

        final ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        final String json = getIntent().getStringExtra(EXTRA_ENTRY);
        if (json == null) {
            finish();
            return;
        }
        final SelfHostedHealthLogEntry entry;
        try {
            entry = SelfHostedHealthLogEntry.fromJson(new JSONObject(json));
        } catch (final JSONException e) {
            LOG.warn("Could not parse sync log entry", e);
            finish();
            return;
        }
        bind(entry);
    }

    private void bind(final SelfHostedHealthLogEntry entry) {
        final boolean ok = entry.getSuccess();
        final TextView statusText = findViewById(R.id.detailStatusText);
        statusText.setText(ok
                ? R.string.selfhosted_health_log_status_success
                : R.string.selfhosted_health_log_status_failed);

        ((TextView) findViewById(R.id.detailUrl)).setText(entry.getUrl());
        ((TextView) findViewById(R.id.detailTimestamp)).setText(fullFormat.format(new Date(entry.getTimestampMs())));
        ((TextView) findViewById(R.id.detailHttpCode)).setText(
                entry.getHttpCode() > 0 ? String.valueOf(entry.getHttpCode()) : "—");
        ((TextView) findViewById(R.id.detailResponseTime)).setText(
                getString(R.string.selfhosted_health_log_response_time_ms, entry.getResponseTimeMs()));
        ((TextView) findViewById(R.id.detailSyncType)).setText(entry.getManual()
                ? R.string.selfhosted_health_log_sync_type_manual
                : R.string.selfhosted_health_log_sync_type_auto);
        ((TextView) findViewById(R.id.detailRecords)).setText(String.valueOf(entry.getRecords()));
        ((TextView) findViewById(R.id.detailDevice)).setText(entry.getDeviceName());

        final String message = entry.getMessage();
        if (!ok && message != null && !message.isEmpty()) {
            findViewById(R.id.detailErrorContainer).setVisibility(View.VISIBLE);
            ((TextView) findViewById(R.id.detailError)).setText(message);
        }

        findViewById(R.id.detailPayload).setVisibility(View.GONE);
    }

    @Override
    public void onCreateMenu(@NonNull final Menu menu, @NonNull final MenuInflater menuInflater) {
        // No copy action: request bodies are intentionally never retained.
    }

    @Override
    public boolean onMenuItemSelected(@NonNull final MenuItem menuItem) {
        return false;
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
