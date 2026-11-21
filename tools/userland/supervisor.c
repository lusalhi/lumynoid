// SPDX-License-Identifier: Apache-2.0
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <sys/prctl.h>
#include <sys/wait.h>
#include <unistd.h>

enum { SUPERVISOR_ERROR = 125 };

static void start_child(pid_t supervisor, char **command) {
    if (setpgid(0, 0) < 0 || prctl(PR_SET_PDEATHSIG, SIGKILL, 0, 0, 0) < 0) {
        perror("configure child");
        _exit(SUPERVISOR_ERROR);
    }
    if (getppid() != supervisor) _exit(SUPERVISOR_ERROR);

    // The control pipe belongs exclusively to the supervisor.
    int input = open("/dev/null", O_RDONLY | O_CLOEXEC);
    if (input < 0 || dup2(input, STDIN_FILENO) < 0) {
        perror("open child stdin");
        _exit(SUPERVISOR_ERROR);
    }
    close(input);
    execv(command[0], command);
    perror("exec child");
    _exit(SUPERVISOR_ERROR);
}

static void kill_adopted_children(void) {
    // Forks which changed process group are reparented here once their parent dies.
    // PRoot's EXITKILL also covers tracees if the Android kernel hides this proc file.
    FILE *children = fopen("/proc/thread-self/children", "re");
    if (children == NULL) return;
    int pid;
    while (fscanf(children, "%d", &pid) == 1) {
        if (pid > 0) kill(pid, SIGKILL);
    }
    fclose(children);
}

static int reap_tree(pid_t child) {
    // Keep the group leader unreaped until after signalling its process group.
    kill(-child, SIGKILL);
    kill(child, SIGKILL);

    int child_status = SUPERVISOR_ERROR << 8;
    for (;;) {
        int status;
        pid_t reaped = waitpid(-1, &status, WNOHANG);
        if (reaped == child) child_status = status;
        if (reaped > 0) continue;
        if (reaped < 0) {
            if (errno == EINTR) continue;
            if (errno == ECHILD) break;
            perror("reap children");
            return SUPERVISOR_ERROR;
        }
        kill_adopted_children();
        poll(NULL, 0, 10);
    }
    if (WIFEXITED(child_status)) return WEXITSTATUS(child_status);
    if (WIFSIGNALED(child_status)) return 128 + WTERMSIG(child_status);
    return SUPERVISOR_ERROR;
}

int main(int argc, char **argv) {
    if (argc < 2) return SUPERVISOR_ERROR;
    if (prctl(PR_SET_CHILD_SUBREAPER, 1, 0, 0, 0) < 0) {
        perror("configure supervisor");
        return SUPERVISOR_ERROR;
    }

    pid_t supervisor = getpid();
    pid_t child = fork();
    if (child < 0) {
        perror("fork");
        return SUPERVISOR_ERROR;
    }
    if (child == 0) start_child(supervisor, argv + 1);

    // Complete group setup on either side of fork; the child may have executed or exited.
    if (setpgid(child, child) < 0 && errno != EACCES && errno != ESRCH) {
        perror("configure child group");
        reap_tree(child);
        return SUPERVISOR_ERROR;
    }

    struct pollfd control = { .fd = STDIN_FILENO, .events = POLLIN };
    for (;;) {
        siginfo_t info = {0};
        if (waitid(P_PID, child, &info, WEXITED | WNOHANG | WNOWAIT) < 0) {
            if (errno == EINTR) continue;
            perror("watch child");
            break;
        }
        if (info.si_pid == child) break;

        int ready = poll(&control, 1, 25);
        if (ready > 0) break;  // EOF, input, or an error ends this task's lifetime.
        if (ready < 0 && errno != EINTR) {
            perror("watch control pipe");
            break;
        }
    }
    return reap_tree(child);
}
