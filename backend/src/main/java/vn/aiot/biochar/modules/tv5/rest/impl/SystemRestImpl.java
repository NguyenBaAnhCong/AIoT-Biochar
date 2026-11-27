package vn.aiot.biochar.modules.tv5.rest.impl;

import java.util.*;
import org.springframework.web.bind.annotation.*;
import vn.aiot.biochar.modules.tv5.rest.ISystemRest;
import vn.aiot.biochar.modules.tv5.service.ISystemService;

@RestController
@RequestMapping("/api/v1/tv5")
public class SystemRestImpl extends RestSupport implements ISystemRest {
    private final ISystemService system;
    public SystemRestImpl(ISystemService system) { this.system = system; }
    @GetMapping("/health")
    public Object health() { return data(system.health()); }
}
