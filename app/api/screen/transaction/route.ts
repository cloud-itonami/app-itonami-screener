import { NextResponse } from "next/server"
import { executeKsqlQuery } from "@/lib/ksqldb-client"

interface Transaction {
  transactionId: string
  amount: number
  currency: string
  customerId: string
}

interface TransactionRule {
  RULE_TYPE: string
  THRESHOLD: number
}

/**
 * Fallback currency-transaction threshold, in USD, used when ksqlDB does not
 * answer with a live rule.
 *
 * Provenance: data/sanctions-sources.edn -> :thresholds -> :ctr-usd, which cites
 * 31 CFR 1010.311 ("a transaction in currency of more than $10,000"). That is
 * also why the comparison below is `>` and not `>=`.
 *
 * scripts/verify-sanctions-sources.cljs reads this literal and fails when it and
 * the catalog disagree, so the number cannot drift away from the rule it claims.
 */
const DEFAULT_TRANSACTION_THRESHOLD_USD = 10000

/**
 * This API endpoint simulates a transaction screening service.
 * It receives transaction data, fetches the latest rule threshold from ksqlDB,
 * and then applies the rule to screen the transaction.
 */
export async function POST(request: Request) {
  try {
    const transaction: Transaction = await request.json()

    if (!transaction.transactionId || !transaction.amount) {
      return NextResponse.json({ message: "Invalid transaction data provided." }, { status: 400 })
    }

    // 1. Define the ksqlDB query to get the latest rule.
    // We assume you have a TABLE named 'currentRules' as designed previously.
    const ksql = `SELECT THRESHOLD FROM currentRules WHERE RULE_TYPE = 'TRANSACTION_MONITORING';`

    console.log(`Executing ksqlDB query to fetch transaction threshold: ${ksql}`)

    // 2. Execute the query using our client
    const results = await executeKsqlQuery<TransactionRule>(ksql)

    // 3. Extract the threshold from the query result.
    // Use a default value if the rule is not found for any reason.
    const threshold = results.length > 0 ? results[0].THRESHOLD : DEFAULT_TRANSACTION_THRESHOLD_USD
    console.log(`Current live transaction threshold is: ${threshold}`)

    // 4. Apply the rule to the incoming transaction
    let isFlagged = false
    let reason = "Transaction amount is within the acceptable limit."

    if (transaction.amount > threshold) {
      isFlagged = true
      reason = `Transaction amount of ${transaction.amount} exceeds the current threshold of ${threshold}.`
    }

    // 5. Return the screening result
    const screeningResult = {
      transactionId: transaction.transactionId,
      isFlagged,
      reason,
      appliedThreshold: threshold,
      timestamp: new Date().toISOString(),
    }

    return NextResponse.json(screeningResult)
  } catch (error) {
    console.error("An error occurred during transaction screening:", error)
    return NextResponse.json({ message: "Internal Server Error during screening." }, { status: 500 })
  }
}
