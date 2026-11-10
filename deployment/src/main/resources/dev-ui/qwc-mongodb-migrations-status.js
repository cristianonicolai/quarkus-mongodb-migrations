import { QwcHotReloadElement, html, css } from 'qwc-hot-reload-element';
import { JsonRpc } from 'jsonrpc';
import { notifier } from 'notifier';
import { columnBodyRenderer } from '@vaadin/grid/lit.js';
import '@vaadin/grid';
import '@vaadin/button';
import '@vaadin/icon';
import '@vaadin/progress-bar';
import '@qomponent/qui-badge';

/**
 * Live status of the MongoDB migrations: which are applied or pending, their latest failures, who holds the lock,
 * and a button to run the pending migrations.
 */
export class QwcMongodbMigrationsStatus extends QwcHotReloadElement {

    jsonRpc = new JsonRpc(this);

    static styles = css`
        :host {
            display: flex;
            flex-direction: column;
            gap: 10px;
            padding: 10px;
        }
        .toolbar {
            display: flex;
            align-items: center;
            justify-content: space-between;
            gap: 10px;
        }
        .lock {
            color: var(--lumo-secondary-text-color);
        }
        .failure {
            color: var(--lumo-error-text-color);
            white-space: normal;
        }
        .muted {
            color: var(--lumo-secondary-text-color);
        }
        vaadin-grid::part(cell) {
            white-space: normal;
        }
    `;

    static properties = {
        _status: { state: true },
        _error: { state: true },
        _running: { state: true },
    };

    constructor() {
        super();
        this._status = null;
        this._error = null;
        this._running = false;
    }

    connectedCallback() {
        super.connectedCallback();
        this.hotReload();
    }

    hotReload() {
        this.jsonRpc.getStatus().then(response => {
            this._status = response.result;
            this._error = null;
        }).catch(error => {
            this._error = this._errorMessage(error);
        });
    }

    render() {
        if (this._error) {
            return html`<span class="failure">Could not load the migration status: ${this._error}</span>`;
        }
        if (!this._status) {
            return html`<vaadin-progress-bar indeterminate></vaadin-progress-bar>`;
        }
        return html`
            <div class="toolbar">
                <span class="lock">${this._renderLock(this._status.lock)}</span>
                <vaadin-button theme="primary" ?disabled=${this._running || this._status.pending === 0}
                        @click=${() => this._migrate()}>
                    <vaadin-icon icon="font-awesome-solid:play" slot="prefix"></vaadin-icon>
                    ${this._running ? 'Running…' : `Run pending migrations (${this._status.pending})`}
                </vaadin-button>
            </div>
            <vaadin-grid .items=${this._status.migrations} theme="no-border row-stripes" all-rows-visible>
                <vaadin-grid-column header="Order" path="order" auto-width flex-grow="0"></vaadin-grid-column>
                <vaadin-grid-column header="Id" path="id" auto-width></vaadin-grid-column>
                <vaadin-grid-column header="Status" auto-width
                        ${columnBodyRenderer(migration => this._renderState(migration), [])}></vaadin-grid-column>
                <vaadin-grid-column header="Applied" width="16em" flex-grow="1"
                        ${columnBodyRenderer(migration => this._renderApplied(migration), [])}></vaadin-grid-column>
                <vaadin-grid-column header="Runs" path="runCount" auto-width flex-grow="0"></vaadin-grid-column>
                <vaadin-grid-column header="Changed" auto-width flex-grow="0"
                        ${columnBodyRenderer(migration => this._renderChanged(migration), [])}></vaadin-grid-column>
                <vaadin-grid-column header="Latest failure" width="16em" flex-grow="2"
                        ${columnBodyRenderer(migration => this._renderFailure(migration.lastFailure), [])}></vaadin-grid-column>
            </vaadin-grid>`;
    }

    _renderLock(lock) {
        if (!lock) {
            return 'Lock is free';
        }
        if (lock.expired) {
            return `Lock held by ${lock.instanceId}, expired at ${this._time(lock.expiresAt)}`;
        }
        return `Lock held by ${lock.instanceId} until ${this._time(lock.expiresAt)}`;
    }

    _renderState(migration) {
        if (migration.lastFailure) {
            return html`<qui-badge level="error" small><span>Failed</span></qui-badge>`;
        }
        if (!migration.applied) {
            return html`<qui-badge level="warning" small><span>Pending</span></qui-badge>`;
        }
        if (migration.runAlways) {
            return html`<qui-badge level="contrast" small><span>Runs always</span></qui-badge>`;
        }
        return html`<qui-badge level="success" small><span>Applied</span></qui-badge>`;
    }

    _renderApplied(migration) {
        if (!migration.applied) {
            return html`<span class="muted">—</span>`;
        }
        const last = migration.runAlways && migration.lastAppliedAt !== migration.appliedAt
                ? html`<br><span class="muted">last ${this._time(migration.lastAppliedAt)}</span>`
                : '';
        return html`${this._time(migration.appliedAt)} <span class="muted">by ${migration.appliedBy}</span>${last}`;
    }

    _renderChanged(migration) {
        if (migration.documentsChanged === null || migration.documentsChanged === undefined) {
            return html`<span class="muted">—</span>`;
        }
        return html`${migration.documentsChanged.toLocaleString()}`;
    }

    _renderFailure(failure) {
        if (!failure) {
            return '';
        }
        const rollback = failure.rollbackErrorMessage
                ? html`<br>Rollback also failed: ${failure.rollbackErrorMessage}`
                : '';
        const changed = failure.documentsChangedBeforeFailure != null
                ? html`<br>${failure.documentsChangedBeforeFailure.toLocaleString()} documents changed before it failed`
                : '';
        return html`<span class="failure">${this._time(failure.failedAt)}: ${failure.errorMessage}${rollback}${changed}</span>`;
    }

    _time(iso) {
        return iso ? new Date(iso).toLocaleString() : '';
    }

    _migrate() {
        this._running = true;
        this.jsonRpc.migrate().then(response => {
            const result = response.result;
            if (result.success) {
                notifier.showSuccessMessage(result.ran.length === 0
                        ? 'No pending migrations'
                        : `Ran ${result.ran.length} migration(s): ${this._escape(result.ran.join(', '))}`);
            } else {
                notifier.showErrorMessage(`Migration run failed: ${this._escape(result.error)}`, null, 10);
            }
        }).catch(error => {
            notifier.showErrorMessage(`Migration run failed: ${this._escape(this._errorMessage(error))}`, null, 10);
        }).finally(() => {
            this._running = false;
            this.hotReload();
        });
    }

    /**
     * The message of a rejected JSON-RPC call.
     */
    _errorMessage(error) {
        return error?.error?.message ?? error?.message ?? String(error);
    }

    /**
     * The notifier renders its message as HTML, and error messages can contain database values, so text passed to it
     * is escaped. Values rendered in templates are escaped by Lit already.
     */
    _escape(text) {
        return String(text)
                .replaceAll('&', '&amp;')
                .replaceAll('<', '&lt;')
                .replaceAll('>', '&gt;')
                .replaceAll('"', '&quot;')
                .replaceAll("'", '&#39;');
    }
}
customElements.define('qwc-mongodb-migrations-status', QwcMongodbMigrationsStatus);
