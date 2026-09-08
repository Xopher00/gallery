---
name: budget-tracker
description: Track daily expenses and income. Use when the user wants to log spending, record a purchase, add income, or see a budget summary.
---

# Budget Tracker

## Categories
Always use one of these exact strings: Food, Transport, Shopping, Entertainment, Bills, Health, Other

## Actions

### 1. Log Expense
- script name: `index.html`
- data JSON:
  - `action`: "log_expense"
  - `amount`: Number (positive, e.g. 15.50)
  - `category`: String (one from the list above)
  - `note`: String (optional, brief description)
  - `date`: String ("today", "yesterday", or "YYYY-MM-DD")

### 2. Log Income
- script name: `index.html`
- data JSON:
  - `action`: "log_income"
  - `amount`: Number (positive)
  - `note`: String (optional)
  - `date`: String ("today", "yesterday", or "YYYY-MM-DD")

### 3. Get Summary / Dashboard
- script name: `index.html`
- data JSON:
  - `action`: "get_summary"
  - `days`: Number (default 30)
  - `show_dashboard`: Boolean (true to show visual dashboard)

### 4. Get History
- script name: `index.html`
- data JSON:
  - `action`: "get_history"
  - `days`: Number (default 7)

### 5. Delete Entry
- script name: `index.html`
- data JSON:
  - `action`: "delete_entry"
  - `id`: String (entry ID shown in history)

### 6. Wipe All Data
- script name: `index.html`
- data JSON:
  - `action`: "wipe_data"

## Sample Commands
- "Log $15 for lunch today"
- "I spent $50 on groceries yesterday"
- "Add income of $2000 for this month's salary"
- "Show my spending for the last 30 days"
- "How much have I spent on food this week?"
- "Show my budget dashboard"
- "Delete all my budget data"
