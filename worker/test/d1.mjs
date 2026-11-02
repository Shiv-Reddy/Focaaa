import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

export function database() {
  const db = new DatabaseSync(':memory:');
  db.exec(readFileSync(fileURLToPath(new URL('../migrations/0001_relay.sql', import.meta.url)), 'utf8'));
  db.exec(readFileSync(fileURLToPath(new URL('../migrations/0002_message_status.sql', import.meta.url)), 'utf8'));
  db.exec(readFileSync(fileURLToPath(new URL('../migrations/0003_media.sql', import.meta.url)), 'utf8'));
  let queryCount = 0;
  let queryLimit = Infinity;
  function charge() {
    if (++queryCount > queryLimit) throw new Error('D1 per-invocation query limit exceeded');
  }
  const prepare = sql => ({
    bind(...values) {
      if (values.length > 100) throw new Error('D1 bound-parameter limit exceeded');
      const statement = db.prepare(sql);
      return {
        first: () => { charge(); return statement.get(...values) ?? null; },
        all: () => { charge(); return { results: statement.all(...values) }; },
        run: () => { charge(); return { meta: statement.run(...values) }; },
        _statement: statement,
        _values: values
      };
    }
  });
  return {
    prepare,
    batch(statements) {
      charge();
      db.exec('BEGIN IMMEDIATE');
      try {
        const result = statements.map(s => ({ meta: s._statement.run(...s._values) }));
        db.exec('COMMIT');
        return result;
      } catch (error) {
        db.exec('ROLLBACK');
        throw error;
      }
    },
    limitQueries(limit) { queryLimit = limit; queryCount = 0; },
    clearQueryLimit() { queryLimit = Infinity; queryCount = 0; },
    close: () => db.close()
  };
}
