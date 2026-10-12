package com.nonononoki.alovoa.cube;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * CubeOS: registers the cube entities and repositories next to Alovoa's (AlovoaApplication pins its scan
 * packages to com.nonononoki.alovoa.entity / .repo; @EntityScan packages are additive).
 */
@Configuration
@EnableJpaRepositories("com.nonononoki.alovoa.cube.repo")
@EntityScan("com.nonononoki.alovoa.cube.entity")
public class CubeJpaConfig {
}
