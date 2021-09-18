TestPT : UnitTest {

	var p;

	setUp {
		p = PT.new;
	}

	tearDown {
		var done = false;
		p.clear;
		SystemClock.sched(0.5, {done = true});
		this.wait({done}, "waiting for some time to let cleanup happen", 1);
	}

	test_loadFresh {
		p.load("
#1
TRI I1
#9
SIN 440
* IT $1.1 0.5
"
		);
	}

	test_setFadeTime {
		p.load("
#1
TRI I1
#9
SIN 440
* IT $1.1 0.5
"
		);
		p.setFadeTime(0, 0, 3);
		this.assertEquals(p.scripts[0].refs.size, 1);
		p.scripts[0].refs.do { |r|
			this.assertEquals(r[1].line, "TRI I1");
			this.assertEquals(r[1].proxy.fadeTime, 3);
		};
	}

	test_replaceStaysControl {
		p.load("
#1
TRI I1
#9
SIN 440
* IT $1.1 0.5
"
		);
		p.replace(0, 0, "+ 0.5 * 0.5 TRI I1");
		this.assertEquals(p.scripts[0].refs.size, 1);
		p.scripts[0].refs.do { |r|
			this.assertEquals(r.out.rate, \control);
		};
	}
}

TestPTScriptNet : UnitTest {
	var parser;
	var net;

	setUp {
        // this will be called before each test
		parser = PTParser.default;
		net = PTScriptNet.new(parser, [], [parser.parse("SIN 440", Dictionary.new)]);
    }

	test_initializationToSin {
		this.assertEquals(net.out.rate, \audio);
	}

	test_insertPassthrough {
		net.insertPassthrough(1);
		this.assertEquals(net.lines[0], "IT");
	}

	test_insert {
		net.insert(1, "* IT SIN 1");
		this.assertEquals(net.lines[0], "* IT SIN 1");
		net.insert(1, "* IT SIN 2");
		this.assertEquals(net.lines[0], "* IT SIN 2");
		this.assertEquals(net.lines[1], "* IT SIN 1");
	}

	test_keepAudioRate {
		var p = net.add("* IT SIN 1");
		this.assert(p.propagate.not);
		this.assert(net.out.rate == \audio);
		this.assertEquals(net.lines[0], "* IT SIN 1");
	}

	test_makeControlRate {
		var p = net.add("SIN 1");
		this.assert(p.propagate);
		this.assertEquals(net.out.rate, \control);
	}

	test_replaceOnly {
		var p = net.add("* IT SIN 1");
		this.assert(p.propagate.not);
		p = net.prepareReplace(1, "SIN 1");
		this.assert(p.propagate);
		p.commit;
		this.assertEquals(net.out.rate, \control);
		this.assertEquals(net.lines[0], "SIN 1");
	}

	test_replacePropagates {
		var p;
		net = PTScriptNet.new(parser, ["SIN 1", "* IT SIN 2"], [parser.parse("SIN 440")]);
		this.assertEquals(net.out.rate, \control);
		p = net.prepareReplace(1, "SIN 440");
		this.assert(p.propagate);
		p.commit;
		this.assertEquals(net.out.rate, \audio);
		this.assertEquals(net.lines[0], "SIN 440");
		this.assertEquals(net.lines[1], "* IT SIN 2");
	}

	test_replaceFails {
		var p;
		var e;
		net = PTScriptNet.new(parser, ["SIN 1", "* IT SIN 2"], [parser.parse("SIN 440")]);
		this.assertEquals(net.out.rate, \control);
		try {
			p = net.prepareReplace(1, "WOW BAD");
		} { |err|
			e = err;
		};
		this.assert(e != nil);
		this.assertEquals(net.lines[0], "SIN 1");
		this.assertEquals(net.lines[1], "* IT SIN 2");
	}
}