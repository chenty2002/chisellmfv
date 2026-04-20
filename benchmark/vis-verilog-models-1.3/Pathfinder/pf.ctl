\define meteoIdle      meteo.state[1:0]=b00
\define meteoLocking   meteo.state[1:0]=b01
\define meteoBusy      meteo.state[1:0]=b10
\define cmIdle         cm.state=0
\define cmBusy         cm.state=1
\define busMgmtIdle    busMgmt.state[1:0]=b00
\define busMgmtLocking busMgmt.state[1:0]=b01

# FAIL: it is always possible to return to the state in which the
#       low priority process is idle

AG(EF(\meteoIdle));

# FAIL: liveness of the bus management task.

AG(\busMgmtLocking -> AF(\busMgmtIdle));
