SPRING_OPEN_ANNOTATIONS = [
    "org.springframework.stereotype.Component",
    "org.springframework.transaction.annotation.Transactional",
    "org.springframework.scheduling.annotation.Async",
    "org.springframework.cache.annotation.Cacheable",
    "org.springframework.boot.test.context.SpringBootTest",
    "org.springframework.validation.annotation.Validated",
]

def spring_allopen_plugins():
    """Returns the list of allopen plugin labels."""
    return ["//src:allopen_" + a.split(".")[-1].lower() for a in SPRING_OPEN_ANNOTATIONS]
