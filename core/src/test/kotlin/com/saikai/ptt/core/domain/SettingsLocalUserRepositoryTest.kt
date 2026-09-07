package com.saikai.ptt.core.domain

import com.saikai.ptt.core.common.Outcome
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsLocalUserRepositoryTest {

    private var clock = 1_000L
    private var idSeed = 0

    private fun repository(settings: FakeSettingsRepository = FakeSettingsRepository()) =
        settings to SettingsLocalUserRepository(
            settings = settings,
            now = { clock },
            newId = { "id-${++idSeed}" },
        )

    private fun <T> Outcome<T, UserError>.value(): T {
        assertTrue("Expected success, got $this", isSuccess)
        return valueOrNull()!!
    }

    private fun <T> Outcome<T, UserError>.error(): UserError {
        assertTrue("Expected failure, got $this", !isSuccess)
        return errorOrNull()!!
    }

    // --- Creation ------------------------------------------------------------

    @Test
    fun `the first name created becomes the one in use`() = runTest {
        // TC-USER-001. The user is in this flow precisely because there was no
        // name; making them pick the one they just typed would be pointless.
        val (settings, users) = repository()

        val created = users.create("田中").value()

        assertEquals("田中", created.displayName)
        assertEquals(created.id, settings.current().activeUserId)
        assertEquals(created, users.activeUser.first())
    }

    @Test
    fun `later names do not steal the active slot`() = runTest {
        val (_, users) = repository()
        val first = users.create("田中").value()
        users.create("倉庫").value()

        assertEquals(first, users.activeUser.first())
    }

    @Test
    fun `several names are all stored`() = runTest {
        // TC-USER-002.
        val (_, users) = repository()
        listOf("田中", "山田", "倉庫", "保安").forEach { users.create(it).value() }

        assertEquals(
            listOf("田中", "山田", "倉庫", "保安"),
            users.users.first().map { it.displayName },
        )
    }

    @Test
    fun `an invalid name creates nothing`() = runTest {
        val (settings, users) = repository()

        assertEquals(UserError.Name.Blank, users.create("   ").error())

        assertEquals(emptyList<LocalUser>(), settings.current().localUsers)
        assertNull(settings.current().activeUserId)
    }

    // --- Renaming ------------------------------------------------------------

    @Test
    fun `renaming keeps the id and moves only the updated timestamp`() = runTest {
        // TC-USER-004. The id is the identity: history records reference the
        // user who spoke, and a rename must not orphan them
        // (docs/05_DataModel.md section 5).
        val (_, users) = repository()
        val original = users.create("田中").value()
        clock = 9_000L

        val renamed = users.rename(original.id, "管理者").value()

        assertEquals(original.id, renamed.id)
        assertEquals("管理者", renamed.displayName)
        assertEquals("createdAt must not move", original.createdAt, renamed.createdAt)
        assertNotEquals(original.updatedAt, renamed.updatedAt)
        assertEquals(9_000L, renamed.updatedAt)
    }

    @Test
    fun `renaming an unknown id fails and changes nothing`() = runTest {
        val (settings, users) = repository()
        users.create("田中").value()
        val before = settings.current()

        assertEquals(UserError.NotFound("nope"), users.rename("nope", "山田").error())
        assertEquals(before.localUsers, settings.current().localUsers)
    }

    @Test
    fun `an invalid new name leaves the old one intact`() = runTest {
        val (_, users) = repository()
        val user = users.create("田中").value()

        users.rename(user.id, "").error()

        assertEquals("田中", users.users.first().single().displayName)
    }

    // --- Deletion ------------------------------------------------------------

    @Test
    fun `a name that is not in use can be deleted`() = runTest {
        // TC-USER-005.
        val (_, users) = repository()
        users.create("田中").value()
        val spare = users.create("倉庫").value()

        assertTrue(users.delete(spare.id).isSuccess)
        assertEquals(listOf("田中"), users.users.first().map { it.displayName })
    }

    @Test
    fun `the last remaining name cannot be deleted`() = runTest {
        // TC-USER-006. Without a name there is no identity to transmit and PTT
        // is unavailable (PRD section 25), so this would strand the user in the
        // first-run flow with their history still on the device.
        val (_, users) = repository()
        val only = users.create("田中").value()

        assertEquals(UserError.CannotDeleteLastUser, users.delete(only.id).error())
        assertEquals(1, users.users.first().size)
    }

    @Test
    fun `the name in use cannot be deleted directly`() = runTest {
        // TC-USER-007. Switching first is required so the user chooses who they
        // become, rather than the app picking for them.
        val (_, users) = repository()
        val active = users.create("田中").value()
        users.create("倉庫").value()

        assertEquals(UserError.CannotDeleteActiveUser(active.id), users.delete(active.id).error())
        assertEquals(2, users.users.first().size)
    }

    @Test
    fun `switching first then deleting works`() = runTest {
        val (_, users) = repository()
        val tanaka = users.create("田中").value()
        val soko = users.create("倉庫").value()

        users.switchActive(soko.id).value()

        assertTrue(users.delete(tanaka.id).isSuccess)
        assertEquals(soko, users.activeUser.first())
    }

    @Test
    fun `deleting an unknown id fails`() = runTest {
        val (_, users) = repository()
        users.create("田中").value()
        assertEquals(UserError.NotFound("nope"), users.delete("nope").error())
    }

    // --- Switching -----------------------------------------------------------

    @Test
    fun `switching changes who is in use`() = runTest {
        // TC-USER-003.
        val (_, users) = repository()
        users.create("田中").value()
        val soko = users.create("倉庫").value()

        assertEquals(soko, users.switchActive(soko.id).value())
        assertEquals(soko, users.activeUser.first())
    }

    @Test
    fun `switching to an unknown id fails and leaves the current one`() = runTest {
        val (_, users) = repository()
        val tanaka = users.create("田中").value()

        assertEquals(UserError.NotFound("nope"), users.switchActive("nope").error())
        assertEquals(tanaka, users.activeUser.first())
    }

    // --- Repair --------------------------------------------------------------

    @Test
    fun `a dangling active pointer reads as no active user`() = runTest {
        // docs/05_DataModel.md section 6: repair, never crash.
        val settings = FakeSettingsRepository(
            AppSettings(
                localUsers = listOf(LocalUser("u1", "田中", 1, 1)),
                activeUserId = "deleted-long-ago",
            ),
        )
        val (_, users) = repository(settings)

        assertNull(users.activeUser.first())
    }

    @Test
    fun `a dangling active pointer is cleared on the next write`() = runTest {
        // Clearing rather than promoting some other name: the active user is
        // the identity transmitted with every packet, and speaking as a name
        // the user did not choose is worse than being asked to choose one.
        val settings = FakeSettingsRepository(
            AppSettings(
                localUsers = listOf(LocalUser("u1", "田中", 1, 1)),
                activeUserId = "deleted-long-ago",
            ),
        )
        val (_, users) = repository(settings)

        users.create("倉庫").value()

        assertNull(
            "The dangling pointer must not survive",
            settings.current().activeUserId?.takeIf { it == "deleted-long-ago" },
        )
    }

    // --- Persistence ---------------------------------------------------------

    @Test
    fun `everything survives a restart`() = runTest {
        val settings = FakeSettingsRepository()
        val (_, first) = repository(settings)
        first.create("田中").value()
        val soko = first.create("倉庫").value()
        first.switchActive(soko.id).value()

        // A new repository over the same store is what a restart looks like.
        val (_, second) = repository(settings)

        assertEquals(listOf("田中", "倉庫"), second.users.first().map { it.displayName })
        assertEquals(soko, second.activeUser.first())
    }
}
