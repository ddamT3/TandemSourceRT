# Multi-Pump Support

Accounts may contain multiple pumps.

## Strategy

1. Discover account.
2. Discover devices.
3. Download BFF pump-log JSON for discovered assignments.
4. Adapt each response independently in Kotlin.
5. Merge chronologically.

No device identifiers should be hardcoded.

Raw diagnostic export selects the most recent pump assignment and skips
assignments with no events. Runtime selection and merging must continue to
avoid hardcoded serial numbers or assignment IDs.
