import { describe, expect, it } from 'vitest';
import { mkTicket } from '../test-utils';
import type { Team } from '../types';
import { ALL_DISTRICTS, districtName, teamInDistrict, ticketDistrict, ticketInDistrict } from './districts';

const delhi = mkTicket({ lat: 28.63, lon: 77.21 });
const puri = mkTicket({ lat: 19.81, lon: 85.83 });
const noFix = mkTicket({ lat: null, lon: null, has_location: false });
const team = (lat: number, lon: number): Team => ({
  id: 't', name: 't', kind: 'medical', capacity: 1, load: 0, base_lat: lat, base_lon: lon, status: 'free',
});

describe('districts', () => {
  it('assigns GPS tickets to the nearest district', () => {
    expect(ticketDistrict(delhi)).toBe('delhi');
    expect(ticketDistrict(puri)).toBe('puri');
  });

  it('uses the estimated position when there is no fix', () => {
    const est = mkTicket({ lat: null, lon: null, has_location: false, geo: { lat: 28.65, lon: 77.23, source: 'mesh', radius_m: 110 } });
    expect(ticketDistrict(est)).toBe('delhi');
  });

  it('never hides a ticket it cannot place', () => {
    expect(ticketDistrict(noFix)).toBeNull();
    expect(ticketInDistrict(noFix, 'delhi')).toBe(true);
    expect(ticketInDistrict(noFix, 'puri')).toBe(true);
  });

  it('filters placed tickets and teams by selection', () => {
    expect(ticketInDistrict(delhi, 'puri')).toBe(false);
    expect(ticketInDistrict(delhi, 'delhi')).toBe(true);
    expect(ticketInDistrict(delhi, ALL_DISTRICTS)).toBe(true);
    expect(teamInDistrict(team(28.55, 77.2), 'delhi')).toBe(true);
    expect(teamInDistrict(team(28.55, 77.2), 'puri')).toBe(false);
    expect(teamInDistrict(team(20.085, 86.095), 'puri')).toBe(true);
  });

  it('names the selection', () => {
    expect(districtName('delhi')).toBe('Delhi');
    expect(districtName(ALL_DISTRICTS)).toBe('All districts');
  });
});
