package com.opsdesk.backend.warmup;

/**
 * A simple bank account that demonstrates encapsulation.
 *
 * WHY is balance private?
 * If balance were public, any code could do: account.balance = -500;
 * That breaks the rule "no overdrawing." By making it private, the ONLY
 * way to change the balance is through deposit() and withdraw(), which
 * enforce the rules. The class owns its data and guarantees its invariants.
 */
public class BankAccount {

    // Private: only this class can touch the balance directly.
    // Outside code must use deposit(), withdraw(), getBalance().
    private double balance;

    /**
     * Java idiom: this.balance = balance
     * The parameter "openingBalance" could be named "balance", but then
     * you'd need "this.balance = balance" to disambiguate. We use a
     * different name here for clarity, but you'll see the this.x = x
     * pattern constantly in Java constructors.
     */
    public BankAccount(double openingBalance) {
        if (openingBalance < 0) {
            throw new IllegalArgumentException("Opening balance cannot be negative");
        }
        this.balance = openingBalance;
    }

    /**
     * Adds money to the account.
     * Rejects zero or negative amounts — a deposit of $0 is meaningless,
     * and a negative deposit would be a sneaky withdrawal without the
     * overdraft check.
     */
    public void deposit(double amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Deposit amount must be positive");
        }
        balance += amount;
    }

    /**
     * Removes money from the account.
     * Rejects zero, negative, or too-large amounts — we never allow
     * the balance to go below zero.
     */
    public void withdraw(double amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Withdrawal amount must be positive");
        }
        if (amount > balance) {
            throw new IllegalArgumentException("Insufficient funds");
        }
        balance -= amount;
    }

    /**
     * The only way for outside code to SEE the balance.
     * This is a "getter" — Java's convention for read-only access.
     * There's no setter, so outside code truly cannot change the balance
     * except through deposit/withdraw.
     */
    public double getBalance() {
        return balance;
    }
}
