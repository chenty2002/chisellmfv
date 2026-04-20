AG EF (p0.Cdevice.readable=1) ;
AG EF (p0.Cdevice.writable=1) ;
AG EF (p1.Cdevice.readable=1) ;
AG EF (p1.Cdevice.writable=1) ;
AG EF (p2.Cdevice.readable=1) ;
AG EF (p2.Cdevice.writable=1) ;
AG ((p0.Cdevice.writable=0) + (p1.Cdevice.writable=0)) ;
AG ((p1.Cdevice.writable=0) + (p2.Cdevice.writable=0)) ;
AG ((p0.Cdevice.writable=0) + (p2.Cdevice.writable=0)) ;
