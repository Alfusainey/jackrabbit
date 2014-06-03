package org.apache.jackrabbit.server.remoting.davex;

import java.util.Collections;
import java.util.List;

import javax.jcr.Node;
import javax.jcr.Session;

import org.apache.jackrabbit.core.config.ImportConfig;
import org.apache.jackrabbit.core.xml.AccessControlImporter;
import org.apache.jackrabbit.core.xml.ProtectedNodeImporter;

final class PseudoConfig extends ImportConfig {

    private final ProtectedNodeImporter aci;

    PseudoConfig() {
        this.aci = new AccessControlImporter();    
    }

    public ImportConfig getWorkspaceConfig() {
        return new PseudoConfig();
    }

    @Override
    public List<? extends ProtectedNodeImporter> getProtectedItemImporters() {
        return Collections.singletonList(aci);   
    }   
}
