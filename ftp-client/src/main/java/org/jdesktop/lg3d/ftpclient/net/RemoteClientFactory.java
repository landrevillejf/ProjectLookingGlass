/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.ftpclient.net;

import java.util.Objects;
import org.jdesktop.lg3d.ftpclient.model.AppSettings;
import org.jdesktop.lg3d.ftpclient.model.Protocol;
import org.jdesktop.lg3d.ftpclient.model.SiteProfile;

/**
 * Builds the {@link RemoteClient} that matches a site's {@link Protocol}, so
 * the session layer never has to know which transport library a given profile
 * uses. FTP and FTPS both go to {@link FtpRemoteClient}; SFTP goes to
 * {@link SftpRemoteClient}.
 *
 * <p>This is the single seam the tests use to inject a fake transport: the
 * session's {@code ConnectionManager} holds a factory and can be given one that
 * returns a mock client instead of opening a real socket.</p>
 */
public class RemoteClientFactory {

    /**
     * Creates an <strong>unconnected</strong> client for a site. The caller is
     * responsible for invoking {@link RemoteClient#connect()} (off the EDT) and
     * for closing it.
     *
     * @param profile  the site to build a client for
     * @param settings the timeouts and buffer size; {@code null} uses defaults
     * @return a client appropriate to the profile's protocol
     */
    public RemoteClient create(SiteProfile profile, AppSettings settings) {
        Objects.requireNonNull(profile, "profile");
        Protocol protocol = profile.getProtocol();
        if (protocol == Protocol.SFTP) {
            return new SftpRemoteClient(profile, settings);
        }
        // FTP and FTPS share the Commons Net adapter; it inspects the protocol to
        // decide whether to negotiate TLS.
        return new FtpRemoteClient(profile, settings);
    }
}
