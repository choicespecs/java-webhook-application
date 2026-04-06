/* ========================================
   Webhook Simulator - Dashboard JS
   WebSocket client + UI update logic
   ======================================== */

$(document).ready(function () {

    // ----------------------------------------
    // Utility helpers
    // ----------------------------------------

    function generateId() {
        return 'evt-' + Math.random().toString(36).substr(2, 9);
    }

    function formatDateTime(dt) {
        if (!dt) return '-';
        const d = new Date(dt);
        return d.toLocaleString();
    }

    function statusBadge(status) {
        const map = {
            'SUCCESS':    'badge-success',
            'FAILED':     'badge-failed',
            'QUEUED':     'badge-queued',
            'PROCESSING': 'badge-processing'
        };
        const cls = map[status] || 'bg-secondary';
        return '<span class="badge ' + cls + '">' + status + '</span>';
    }

    function currentIsoTimestamp() {
        return new Date().toISOString().substring(0, 19);
    }

    // ----------------------------------------
    // WebSocket / STOMP setup
    // ----------------------------------------

    var stompClient = null;

    function connect() {
        var socket = new SockJS('/ws');
        stompClient = Stomp.over(socket);
        stompClient.debug = null; // suppress debug logs

        stompClient.connect({}, function (frame) {
            setWsStatus('connected');

            stompClient.subscribe('/topic/stats', function (message) {
                var stats = JSON.parse(message.body);
                updateStats(stats);
            });

            stompClient.subscribe('/topic/history', function (message) {
                var history = JSON.parse(message.body);
                updateRecentTable(history);
            });

            // Fetch initial data via REST on first connect
            fetchInitialData();

        }, function (error) {
            setWsStatus('disconnected');
            // Attempt reconnect after 3 seconds
            setTimeout(connect, 3000);
        });
    }

    function setWsStatus(state) {
        var $el = $('#ws-status');
        $el.removeClass('connected disconnected');
        if (state === 'connected') {
            $el.addClass('connected').html('<i class="fas fa-circle me-1"></i>Connected');
        } else {
            $el.addClass('disconnected').html('<i class="fas fa-circle me-1"></i>Disconnected');
        }
    }

    function fetchInitialData() {
        $.getJSON('/api/stats', function (stats) {
            updateStats(stats);
        });
        $.getJSON('/api/webhook/history?limit=50', function (history) {
            updateRecentTable(history);
        });
    }

    // ----------------------------------------
    // Stats update
    // ----------------------------------------

    function updateStats(stats) {
        var byStatus = stats.byStatus || {};
        var total    = stats.total || 0;
        var success  = byStatus['SUCCESS']    || 0;
        var failed   = byStatus['FAILED']     || 0;
        var queued   = byStatus['QUEUED']     || 0;
        var proc     = byStatus['PROCESSING'] || 0;

        $('#stat-total').text(total);
        $('#stat-success').text(success);
        $('#stat-failed').text(failed);
        $('#stat-pending').text(queued + proc);

        updateEventTypeBreakdown(stats.byEventType || {}, total);
    }

    function updateEventTypeBreakdown(byEventType, total) {
        var $container = $('#event-type-list');
        $container.empty();

        var keys = Object.keys(byEventType);
        if (keys.length === 0) {
            $container.html('<p class="text-muted small mb-0">No data yet</p>');
            return;
        }

        keys.sort(function(a, b) { return byEventType[b] - byEventType[a]; });

        keys.forEach(function (type) {
            var count = byEventType[type];
            var pct   = total > 0 ? Math.round((count / total) * 100) : 0;
            $container.append(
                '<div class="mb-2">' +
                  '<div class="d-flex justify-content-between small mb-1">' +
                    '<span class="fw-semibold">' + escapeHtml(type) + '</span>' +
                    '<span class="text-muted">' + count + ' (' + pct + '%)</span>' +
                  '</div>' +
                  '<div class="bg-light rounded" style="height:6px;">' +
                    '<div class="event-type-bar" style="width:' + pct + '%;"></div>' +
                  '</div>' +
                '</div>'
            );
        });
    }

    // ----------------------------------------
    // Recent webhooks table
    // ----------------------------------------

    var knownIds = new Set();

    function updateRecentTable(history) {
        var $tbody = $('#recent-tbody');
        var records = history.slice(0, 10); // show last 10

        // Remove the "empty" placeholder row
        if (records.length > 0) {
            $('#empty-row').remove();
        }

        // Build a map of existing rows by id
        var existingRows = {};
        $tbody.find('tr[data-id]').each(function () {
            existingRows[$(this).attr('data-id')] = $(this);
        });

        // Track which ids are in the new payload
        var newIds = new Set(records.map(function(r) { return r.id; }));

        // Update or insert rows
        records.forEach(function (record, idx) {
            var id  = record.id;
            var row = buildRecentRow(record);

            if (existingRows[id]) {
                // Update in place
                existingRows[id].replaceWith(row);
            } else {
                // Prepend new row with highlight animation
                row.addClass('new-row');
                $tbody.prepend(row);
            }
        });

        // Remove rows no longer in the top-10
        $tbody.find('tr[data-id]').each(function () {
            if (!newIds.has($(this).attr('data-id'))) {
                $(this).remove();
            }
        });

        // Keep only 10 rows
        $tbody.find('tr[data-id]').slice(10).remove();

        $('#recent-count').text(history.length + ' events');
    }

    function buildRecentRow(record) {
        return $('<tr>')
            .attr('data-id', record.id)
            .append($('<td>').html('<span class="badge bg-light text-dark border">' + escapeHtml(record.eventType) + '</span>'))
            .append($('<td>').html('<span class="font-monospace small text-muted">' + escapeHtml(record.eventId) + '</span>'))
            .append($('<td>').html(statusBadge(record.status)))
            .append($('<td>').html('<span class="small">' + formatDateTime(record.receivedAt) + '</span>'))
            .append($('<td>').html(
                record.retryCount > 0
                    ? '<span class="badge bg-warning text-dark">' + record.retryCount + '</span>'
                    : '<span class="text-muted">0</span>'
            ));
    }

    // ----------------------------------------
    // Send test webhook form
    // ----------------------------------------

    // Show/hide custom event type input
    $('#eventType').on('change', function () {
        if ($(this).val() === 'custom') {
            $('#custom-event-type-group').show();
        } else {
            $('#custom-event-type-group').hide();
        }
    });

    // Generate random event ID button
    $('#generate-id-btn').on('click', function () {
        $('#eventId').val(generateId());
    });

    // Pre-fill a random event ID on load
    $('#eventId').val(generateId());

    // Form submission
    $('#send-form').on('submit', function (e) {
        e.preventDefault();

        var eventType = $('#eventType').val() === 'custom'
            ? $('#customEventType').val().trim()
            : $('#eventType').val();

        var eventId = $('#eventId').val().trim();
        if (!eventId) eventId = generateId();

        var dataStr = $('#payload-data').val().trim();
        var data    = null;
        if (dataStr) {
            try {
                data = JSON.parse(dataStr);
            } catch (err) {
                showSendResult('error', 'Invalid JSON in data field: ' + err.message);
                return;
            }
        }

        var payload = {
            eventType: eventType,
            eventId:   eventId,
            timestamp: currentIsoTimestamp(),
            data:      data
        };

        var $btn = $('#send-btn');
        $btn.prop('disabled', true).html('<i class="fas fa-spinner fa-spin me-2"></i>Sending...');

        $.ajax({
            url:         '/api/webhook/receive',
            method:      'POST',
            contentType: 'application/json',
            data:        JSON.stringify(payload),
            success: function (response) {
                showSendResult('success', 'Webhook queued! Event ID: ' + response.receivedEventId);
                // Refresh event ID for next send
                $('#eventId').val(generateId());
            },
            error: function (xhr) {
                var msg = 'Error ' + xhr.status;
                try {
                    var body = JSON.parse(xhr.responseText);
                    msg += ': ' + (body.message || xhr.statusText);
                } catch (ignore) {
                    msg += ': ' + xhr.statusText;
                }
                showSendResult('error', msg);
            },
            complete: function () {
                $btn.prop('disabled', false).html('<i class="fas fa-paper-plane me-2"></i>Send Webhook');
            }
        });
    });

    function showSendResult(type, message) {
        var $el = $('#send-result');
        $el.show().removeClass().addClass('alert mt-3');
        if (type === 'success') {
            $el.addClass('alert-success').html('<i class="fas fa-check-circle me-2"></i>' + escapeHtml(message));
        } else {
            $el.addClass('alert-danger').html('<i class="fas fa-exclamation-circle me-2"></i>' + escapeHtml(message));
        }
        // Auto-hide after 5 seconds
        setTimeout(function () { $el.fadeOut(); }, 5000);
    }

    // ----------------------------------------
    // XSS prevention
    // ----------------------------------------

    function escapeHtml(str) {
        if (!str) return '';
        return String(str)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#039;');
    }

    // ----------------------------------------
    // Boot
    // ----------------------------------------

    connect();
});
