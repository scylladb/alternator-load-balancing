import gc


def test_executor_pool():
    from alternator_lb import ExecutorPool

    class FakePool:
        def __init__(self, *args, **kwargs):
            self.submit_counts = 0
            self.shutdown_counts = 0

        def submit(self, *args, **kwargs):
            self.submit_counts += 1

        def shutdown(self, *args, **kwargs):
            pass

    class ExecutorPoolPatch(ExecutorPool):
        @classmethod
        def create_executor(cls):
            return FakePool()

        def get_executor(self):
            return self._executor

    class SomeClass:
        pool = ExecutorPoolPatch()

        def __init__(self, *args, **kwargs):
            self.pool.add_ref()

        def __del__(self):
            self.pool.remove_ref()

        def submit(self, *args, **kwargs):
            self.pool.submit(*args, **kwargs)

    for _ in range(2):
        e = SomeClass()
        e1 = SomeClass()

        e.pool.submit(None)
        e1.pool.submit(None)

        executor = e.pool.get_executor()
        executor1 = e.pool.get_executor()
        assert executor1 == executor
        assert executor.submit_counts == 2

        del e
        del e1
        del executor1
        del executor
        gc.collect()

        assert SomeClass.pool.get_executor() is None
