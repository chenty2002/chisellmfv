// +----------------------------------------------------------------+
// |            Copyright (c) 1994 Stanford University.             |
// |                      All Rights Reserved.                      |
// |                                                                |
// |   This software is distributed with *ABSOLUTELY NO SUPPORT*    |
// |   and *NO WARRANTY*.   Use or reproduction of this code for    |
// |   commerical gains is strictly prohibited.   Otherwise, you    |
// |   are given permission to use or modify this code as long      |
// |   as you do not remove this notice.                            |
// +----------------------------------------------------------------+
//
//  Title: 	Compares
//  Created:	Nov 4 1991
//  Author: 	Ricardo E. Gonzalez
//		<ricardog@bill>
//
//
//  compares.v,v 7.5 1995/01/28 00:50:46 ricardog Exp
//
//  TORCH Research Group.
//  Stanford University.
//	1992.
//
//	Description: This module contains one set of register specifier
//  	    comparators, i.e. four comparators. It takes as input RS or RT
//  	    and generates a 5-bit 1-hot signal that goes to a mux in the
//  	    datapath sections that does the actual bypass.
//
//	Hierarchy: system.processor.regFile.bypass.bypassSpec.compares
//
//  Revision History:
//	Modified:	Sat Apr  9 17:36:57 1994	<ricardog@chroma>
//	* Fixed verilint errors.
//	Modified:	Tue Mar 29 19:01:55 1994	<ricardog@chroma>
//	* Removed old code.
//	Modified:	Feb 20 1992	<ricardog@bill>
//	* Structural version.
//
`include "torch.h"

module compares (
    SrcSpec_s2r,
    ADest_s2e,
    ADest_s2m,
    BDest_s2e,
    BDest_s2m,
    BypassSel_v2r
    );

//
// Inputs
//
input	[6:0]	SrcSpec_s2r;
input	[6:0]	ADest_s2e, ADest_s2m;
input	[6:0]	BDest_s2e, BDest_s2m;

//
// Outputs
//
output	[4:0]	BypassSel_v2r;

//
// Local Wires
//
wire	[4:0]	compareRes_v2r, BypassSel_v2r;

//
// CompareRes_v2r is NOT a 1-hot output type signal, but BypassSel_v2r is.
// This signal is then latched in bypassSpec. 
// Four comparators here. This module (compares) will itself be instantiated
// 4 times, for a total of 16 comparators.
//
assign compareRes_v2r[`NO_BYPASS_BIT] = 1'b1;

COMP_7 compareBm (compareRes_v2r[`BYPASS_BMEM_BIT], SrcSpec_s2r,
				BDest_s2m[`VALID_BIT:0]);
COMP_7 compareAm (compareRes_v2r[`BYPASS_AMEM_BIT], SrcSpec_s2r,
				ADest_s2m[`VALID_BIT:0]);
COMP_7 compareBe (compareRes_v2r[`BYPASS_BEX_BIT], SrcSpec_s2r,
				BDest_s2e[`VALID_BIT:0]);
COMP_7 compareAe (compareRes_v2r[`BYPASS_AEX_BIT], SrcSpec_s2r,
				ADest_s2e[`VALID_BIT:0]);

PRIORITY_5 BypassSel_V2R (BypassSel_v2r, compareRes_v2r);


endmodule				  // compares



