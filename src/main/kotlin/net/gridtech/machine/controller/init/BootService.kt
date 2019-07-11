package net.gridtech.machine.controller.init

import net.gridtech.core.Bootstrap
import net.gridtech.core.data.IHostInfo
import net.gridtech.core.util.hostInfoPublisher
import net.gridtech.machine.model.DataHolder
import net.gridtech.master.HostMaster
import net.gridtech.repository.data.FieldDao
import net.gridtech.repository.data.FieldValueDao
import net.gridtech.repository.data.NodeClassDao
import net.gridtech.repository.data.NodeDao
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import javax.annotation.PostConstruct


@Service
class BootService {
    @Autowired
    lateinit var nodeClassDao: NodeClassDao
    @Autowired
    lateinit var fieldClassDao: FieldDao
    @Autowired
    lateinit var nodeDao: NodeDao
    @Autowired
    lateinit var fieldValueDao: FieldValueDao
    @Autowired
    lateinit var machineInfoService: MachineInfoService

    lateinit var bootstrap: Bootstrap
    lateinit var hostMaster: HostMaster
    lateinit var dataHolder: DataHolder

    @PostConstruct
    fun start() {
        hostInfoPublisher.subscribe { hostInfoChanged(it) }
        bootstrap = Bootstrap(
                true,
                nodeClassDao,
                fieldClassDao,
                nodeDao,
                fieldValueDao
        )
        hostMaster = HostMaster(bootstrap)
        dataHolder = DataHolder(bootstrap, null)
    }

    private fun hostInfoChanged(hostInfo: IHostInfo) {
        if (machineInfoService.hostInfo != hostInfo) {
            machineInfoService.hostInfo = hostInfo
        }
    }
}