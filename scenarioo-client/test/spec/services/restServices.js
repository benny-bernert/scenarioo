/* scenarioo-client
 * Copyright (C) 2014, scenarioo.org Development Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

'use strict';

describe('Service :: restServices', function () {

    beforeEach(module('scenarioo.services'));

    it('should inject ConfigResource', inject(function (ConfigResource) {
        expect(ConfigResource).not.toBeUndefined();
        expect(ConfigResource.get).not.toBeUndefined();
    }));

    describe('HostnameAndPort :: development', function () {

        beforeEach(function () {
            module(function ($provide) {
                $provide.constant('ENV', 'development');
            });
        });

        it('should resolve the host name to local host', inject(function (HostnameAndPort) {
            expect(HostnameAndPort.forNgResource()).toBe('http://localhost\\:8080/scenarioo/');
            expect(HostnameAndPort.forTest()).toBe('http://localhost:8080/scenarioo/');
            expect(HostnameAndPort.forLink()).toBe('http://localhost:8080/scenarioo/');
            expect(HostnameAndPort.forLinkAbsolute()).toBe('http://localhost:8080/scenarioo/');
        }));

    });

    describe('HostnameAndPort :: production', function () {

        beforeEach(function () {
            module(function ($provide) {
                $provide.constant('ENV', 'production');
            });
        });

        it('should resolve the host name to relative host', inject(function (HostnameAndPort, $location) {
            expect(HostnameAndPort.forNgResource()).toBe('');
            expect(HostnameAndPort.forTest()).toBe('');
            expect(HostnameAndPort.forLink()).toBe('');

        }));

        it('returns absolute host based on $location without port', inject(function (HostnameAndPort, $location) {
            spyOn($location, 'absUrl').andReturn('http://myDomain/scenarioo/#/step/Find page/...');

            expect(HostnameAndPort.forLinkAbsolute()).toBe('http://myDomain/scenarioo/');
        }));


        it('returns absolute host based on $location with port', inject(function (HostnameAndPort, $location) {
            spyOn($location, 'absUrl').andReturn('https://myDomain:8080/#/step/Find page/...');

            expect(HostnameAndPort.forLinkAbsolute()).toBe('https://myDomain:8080/');
        }));

    });

});
