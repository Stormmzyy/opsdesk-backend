package com.opsdesk.backend.warmup;

// Boot 3 shipped JUnit 5; Boot 4 ships JUnit 6, with the same org.junit.jupiter imports.
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BankAccountTest {

    @Test
    void depositIncreasesBalance() {
        BankAccount account = new BankAccount(100.0);

        account.deposit(50.0);

        assertEquals(150.0, account.getBalance(), 0.0001);
    }

    @Test
    void negativeDepositIsRejected() {
        BankAccount account = new BankAccount(100.0);

        assertThrows(IllegalArgumentException.class, () -> account.deposit(-20.0));
    }

    @Test
    void withdrawMoreThanBalanceIsRejected() {
        BankAccount account = new BankAccount(100.0);

        assertThrows(IllegalArgumentException.class, () -> account.withdraw(500.0));
    }
}